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
 * Implements the Windows Metafile ([MS-WMF]) and Enhanced Metafile ([MS-EMF] / [MS-EMFPLUS])
 * open specifications published under the Microsoft Open Specifications Promise (OSP).
 */

package com.omnireader.core.engine.impl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class MetafileImage(val picture: Picture, val width: Int, val height: Int) {
    fun draw(canvas: Canvas, dst: RectF) {
        if (width <= 0 || height <= 0 || dst.width() == 0f || dst.height() == 0f) return
        canvas.save()
        canvas.translate(dst.left, dst.top)
        canvas.scale(dst.width() / width, dst.height() / height)
        canvas.drawPicture(picture)
        canvas.restore()
    }

    fun rasterize(maxDim: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val s = min(1f, maxDim.toFloat() / max(width, height))
        val w = max(1, (width * s).toInt())
        val h = max(1, (height * s).toInt())
        val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { return null }
        draw(Canvas(bmp), RectF(0f, 0f, w.toFloat(), h.toFloat()))
        return bmp
    }
}

internal object CleanRoomMetafile {
    private const val MAX_RECORDS = 2_000_000
    private const val MAX_DIM = 4096

    fun isWmf(b: ByteArray): Boolean {
        if (b.size < 18) return false
        val r = PptBytes(b)
        if (r.s32(0) == 0x9AC6CDD7.toInt()) return true
        val type = r.u16(0)
        val hs = r.u16(2)
        return (type == 1 || type == 2) && hs == 9
    }

    fun isEmf(b: ByteArray): Boolean = b.size >= 88 && PptBytes(b).s32(0) == 1 && PptBytes(b).s32(40) == 0x464D4520

    fun decode(b: ByteArray): MetafileImage? = try {
        when {
            isEmf(b) -> Emf(b).play()
            isWmf(b) -> {
                val embedded = extractEmbeddedEmf(b)
                (if (embedded != null && isEmf(embedded)) Emf(embedded).play() else null) ?: Wmf(b).play()
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun extractEmbeddedEmf(b: ByteArray): ByteArray? {
        val r = PptBytes(b)
        var p = if (r.s32(0) == 0x9AC6CDD7.toInt()) 22 else 0
        p += r.u16(p + 2) * 2
        val out = java.io.ByteArrayOutputStream()
        var total = -1
        var n = 0
        while (p + 6 <= b.size && n++ < MAX_RECORDS) {
            val size = r.s32(p).toLong() * 2
            val fn = r.u16(p + 4)
            if (size < 6 || p + size > b.size) break
            if (fn == 0x0626 && size >= 6 + 4 + 34) {
                val esc = r.u16(p + 6)
                val q = p + 10
                if (esc == 0x000F && r.s32(q) == 0x43464D57 && r.s32(q + 4) == 1) {
                    val chunkSize = r.s32(q + 20)
                    val remaining = r.s32(q + 24)
                    total = r.s32(q + 32)
                    val data = q + 34
                    if (chunkSize > 0 && data + chunkSize <= b.size) out.write(b, data, chunkSize)
                    if (remaining == 0) break
                }
            }
            if (fn == 0) break
            p += size.toInt()
        }
        val res = out.toByteArray()
        return if (res.isNotEmpty() && (total <= 0 || res.size >= total)) res else null
    }

    private class GdiPen(val style: Int, val width: Float, val color: Int)
    private class GdiBrush(val style: Int, val color: Int, val hatch: Int, val pattern: Bitmap?)
    private class GdiFont(
        val height: Float, val escapement: Float, val weight: Int, val italic: Boolean,
        val underline: Boolean, val strike: Boolean, val charset: Int, val face: String
    )

    private class Dc {
        var winOrgX = 0f; var winOrgY = 0f; var winExtX = 1f; var winExtY = 1f
        var vpOrgX = 0f; var vpOrgY = 0f; var vpExtX = 1f; var vpExtY = 1f
        var mapMode = 1
        var world = Matrix()
        var pen: GdiPen? = GdiPen(0, 0f, Color.BLACK)
        var brush: GdiBrush? = GdiBrush(0, Color.WHITE, 0, null)
        var font: GdiFont? = null
        var textColor = Color.BLACK
        var bkColor = Color.WHITE
        var bkMode = 2
        var textAlign = 0
        var polyFill = 1
        var curX = 0f; var curY = 0f
        var rop2 = 13

        fun copy(): Dc = Dc().also {
            it.winOrgX = winOrgX; it.winOrgY = winOrgY; it.winExtX = winExtX; it.winExtY = winExtY
            it.vpOrgX = vpOrgX; it.vpOrgY = vpOrgY; it.vpExtX = vpExtX; it.vpExtY = vpExtY
            it.mapMode = mapMode; it.world = Matrix(world); it.pen = pen; it.brush = brush; it.font = font
            it.textColor = textColor; it.bkColor = bkColor; it.bkMode = bkMode; it.textAlign = textAlign
            it.polyFill = polyFill; it.curX = curX; it.curY = curY; it.rop2 = rop2
        }
    }

    private abstract class Player(val data: ByteArray) {
        val r = PptBytes(data)
        var dc = Dc()
        val stack = ArrayList<Dc>()
        lateinit var canvas: Canvas
        var devScaleX = 1f
        var devScaleY = 1f
        var devOffX = 0f
        var devOffY = 0f
        var path: Path? = null
        var clipDepth = 0
        var fixedUnitsPerDevX = 1f
        var fixedUnitsPerDevY = 1f

        var baseCount = 1

        fun begin(picture: Picture, w: Int, h: Int) {
            canvas = picture.beginRecording(w, h)
            canvas.clipRect(0f, 0f, w.toFloat(), h.toFloat())
            baseCount = canvas.save()
        }

        fun pageX(x: Float, y: Float): Float {
            val pts = floatArrayOf(x, y)
            dc.world.mapPoints(pts)
            return pageMapX(pts[0])
        }

        fun pageY(x: Float, y: Float): Float {
            val pts = floatArrayOf(x, y)
            dc.world.mapPoints(pts)
            return pageMapY(pts[1])
        }

        fun pageMapX(x: Float): Float = when (dc.mapMode) {
            7, 8 -> (x - dc.winOrgX) * dc.vpExtX / (if (dc.winExtX == 0f) 1f else dc.winExtX) + dc.vpOrgX
            1 -> x - dc.winOrgX + dc.vpOrgX
            else -> (x - dc.winOrgX) / fixedUnitsPerDevX + dc.vpOrgX
        }

        fun pageMapY(y: Float): Float = when (dc.mapMode) {
            7, 8 -> (y - dc.winOrgY) * dc.vpExtY / (if (dc.winExtY == 0f) 1f else dc.winExtY) + dc.vpOrgY
            1 -> y - dc.winOrgY + dc.vpOrgY
            else -> -(y - dc.winOrgY) / fixedUnitsPerDevY + dc.vpOrgY
        }

        fun ox(x: Float, y: Float): Float = pageX(x, y) * devScaleX + devOffX
        fun oy(x: Float, y: Float): Float = pageY(x, y) * devScaleY + devOffY

        fun scaleLen(v: Float): Float {
            val a = ox(0f, 0f)
            val b = ox(v, 0f)
            val c = oy(0f, 0f)
            val d = oy(0f, v)
            return (abs(b - a) + abs(d - c)) / 2f
        }

        fun scaleLenY(v: Float): Float = abs(oy(0f, v) - oy(0f, 0f)).let { if (it == 0f) abs(ox(v, 0f) - ox(0f, 0f)) else it }

        fun penPaint(): Paint? {
            val p = dc.pen ?: return null
            if (p.style and 0xF == 5) return null
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.STROKE
            paint.color = p.color
            val w = if (p.width <= 0f) 1f else max(scaleLen(p.width), 0.5f)
            paint.strokeWidth = w
            paint.strokeCap = when ((p.style ushr 8) and 0xF) {
                0 -> Paint.Cap.ROUND
                1 -> Paint.Cap.SQUARE
                else -> Paint.Cap.BUTT
            }
            paint.strokeJoin = when ((p.style ushr 12) and 0xF) {
                0 -> Paint.Join.ROUND
                1 -> Paint.Join.BEVEL
                else -> Paint.Join.MITER
            }
            val unit = max(w, 1f)
            when (p.style and 0xF) {
                1 -> paint.pathEffect = DashPathEffect(floatArrayOf(6 * unit, 2 * unit), 0f)
                2 -> paint.pathEffect = DashPathEffect(floatArrayOf(unit, unit * 2), 0f)
                3 -> paint.pathEffect = DashPathEffect(floatArrayOf(6 * unit, 2 * unit, unit, 2 * unit), 0f)
                4 -> paint.pathEffect = DashPathEffect(floatArrayOf(6 * unit, 2 * unit, unit, 2 * unit, unit, 2 * unit), 0f)
            }
            applyRop(paint)
            return paint
        }

        fun brushPaint(): Paint? {
            val b = dc.brush ?: return null
            if (b.style == 1) return null
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            when (b.style) {
                2 -> {
                    paint.shader = hatchShader(b.hatch, b.color, if (dc.bkMode == 2) dc.bkColor else Color.TRANSPARENT)
                }
                3, 5, 6 -> if (b.pattern != null) {
                    paint.shader = BitmapShader(b.pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                } else {
                    paint.color = b.color
                }
                else -> paint.color = b.color
            }
            applyRop(paint)
            return paint
        }

        fun applyRop(paint: Paint) {
            when (dc.rop2) {
                1 -> paint.color = Color.BLACK
                16 -> paint.color = Color.WHITE
                11 -> paint.alpha = 0
                9 -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                15 -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                7, 10 -> paint.blendMode = android.graphics.BlendMode.DIFFERENCE
            }
        }

        fun hatchShader(hatch: Int, fg: Int, bg: Int): Shader {
            val n = 8
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            for (y in 0 until n) for (x in 0 until n) {
                val on = when (hatch) {
                    0 -> y == n / 2
                    1 -> x == n / 2
                    2 -> x == y
                    3 -> x == n - 1 - y
                    4 -> x == n / 2 || y == n / 2
                    5 -> x == y || x == n - 1 - y
                    else -> false
                }
                bmp.setPixel(x, y, if (on) fg else bg)
            }
            val s = max(1f, devScaleX)
            return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setScale(s, s) })
            }
        }

        fun fillType(): Path.FillType = if (dc.polyFill == 2) Path.FillType.WINDING else Path.FillType.EVEN_ODD

        private class PendingMask(
            val bmp: Bitmap, val src: android.graphics.Rect, val dst: RectF,
            val flipX: Boolean, val flipY: Boolean, val rop: Int
        )

        private var pendingMask: PendingMask? = null
        private var lastDibBpp = 0

        fun flushPendingMask() {
            val pm = pendingMask ?: return
            pendingMask = null
            val bmp = neutralizeMaskBitmap(pm.bmp, pm.src, pm.rop == 0x00EE0086)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            paint.xfermode = PorterDuffXfermode(if (pm.rop == 0x00EE0086) PorterDuff.Mode.LIGHTEN else PorterDuff.Mode.MULTIPLY)
            canvas.save()
            if (pm.flipX) canvas.scale(-1f, 1f, pm.dst.centerX(), pm.dst.centerY())
            if (pm.flipY) canvas.scale(1f, -1f, pm.dst.centerX(), pm.dst.centerY())
            canvas.drawBitmap(bmp, null, pm.dst, paint)
            canvas.restore()
        }

        private fun neutralizeMaskBitmap(bmp: Bitmap, src: android.graphics.Rect, dropBlack: Boolean): Bitmap {
            val w = src.width(); val h = src.height()
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, src.left, src.top, w, h)
            for (i in px.indices) {
                val c = px[i]
                val lum = ((c ushr 16) and 0xFF) + ((c ushr 8) and 0xFF) + (c and 0xFF)
                if (dropBlack && lum < 24) px[i] = 0 else if (!dropBlack && lum > 741) px[i] = 0
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        private fun composeMaskedBitmap(mask: Bitmap, maskSrc: android.graphics.Rect, color: Bitmap, colorSrc: android.graphics.Rect, whiteIsOpaque: Boolean): Bitmap {
            val cw = colorSrc.width(); val ch = colorSrc.height()
            val mw = maskSrc.width(); val mh = maskSrc.height()
            val cpx = IntArray(cw * ch)
            val mpx = IntArray(mw * mh)
            color.getPixels(cpx, 0, cw, colorSrc.left, colorSrc.top, cw, ch)
            mask.getPixels(mpx, 0, mw, maskSrc.left, maskSrc.top, mw, mh)
            for (y in 0 until ch) {
                val my = if (mh == ch) y else (y * mh / ch).coerceIn(0, mh - 1)
                val crow = y * cw
                val mrow = my * mw
                for (x in 0 until cw) {
                    val mx = if (mw == cw) x else (x * mw / cw).coerceIn(0, mw - 1)
                    val mc = mpx[mrow + mx]
                    val lum = ((mc ushr 16) and 0xFF) + ((mc ushr 8) and 0xFF) + (mc and 0xFF)
                    val opaque = if (whiteIsOpaque) lum >= 384 else lum < 384
                    cpx[crow + x] = if (opaque) (cpx[crow + x] or (0xFF shl 24)) else 0
                }
            }
            return Bitmap.createBitmap(cpx, cw, ch, Bitmap.Config.ARGB_8888)
        }

        fun drawShape(p: Path, fill: Boolean, stroke: Boolean) {
            flushPendingMask()
            val cur = path
            if (cur != null) {
                cur.addPath(p)
                return
            }
            p.fillType = fillType()
            if (fill) brushPaint()?.let { canvas.drawPath(p, it) }
            if (stroke) penPaint()?.let { canvas.drawPath(p, it) }
        }

        fun polyPath(xs: FloatArray, ys: FloatArray, close: Boolean): Path {
            val p = Path()
            for (i in xs.indices) {
                val x = ox(xs[i], ys[i])
                val y = oy(xs[i], ys[i])
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            if (close && xs.isNotEmpty()) p.close()
            return p
        }

        fun rectPath(l: Float, t: Float, rr: Float, b: Float): Path {
            val p = Path()
            p.moveTo(ox(l, t), oy(l, t))
            p.lineTo(ox(rr, t), oy(rr, t))
            p.lineTo(ox(rr, b), oy(rr, b))
            p.lineTo(ox(l, b), oy(l, b))
            p.close()
            return p
        }

        fun ellipsePath(l: Float, t: Float, rr: Float, b: Float): Path {
            val p = Path()
            val cx = (l + rr) / 2f
            val cy = (t + b) / 2f
            val rx = (rr - l) / 2f
            val ry = (b - t) / 2f
            val n = 64
            for (i in 0..n) {
                val a = i * 2.0 * Math.PI / n
                val x = cx + (rx * cos(a)).toFloat()
                val y = cy + (ry * sin(a)).toFloat()
                if (i == 0) p.moveTo(ox(x, y), oy(x, y)) else p.lineTo(ox(x, y), oy(x, y))
            }
            p.close()
            return p
        }

        fun roundRectPath(l: Float, t: Float, rr: Float, b: Float, w: Float, h: Float): Path {
            val p = Path()
            val rx = min(abs(w) / 2f, abs(rr - l) / 2f)
            val ry = min(abs(h) / 2f, abs(b - t) / 2f)
            val pts = ArrayList<FloatArray>()
            fun arc(cx: Float, cy: Float, a0: Double) {
                for (i in 0..8) {
                    val a = a0 + i * Math.PI / 2 / 8
                    pts.add(floatArrayOf(cx + (rx * cos(a)).toFloat(), cy + (ry * sin(a)).toFloat()))
                }
            }
            val L = min(l, rr); val R = max(l, rr); val T = min(t, b); val B = max(t, b)
            arc(R - rx, T + ry, -Math.PI / 2)
            arc(R - rx, B - ry, 0.0)
            arc(L + rx, B - ry, Math.PI / 2)
            arc(L + rx, T + ry, Math.PI)
            pts.forEachIndexed { i, pt -> if (i == 0) p.moveTo(ox(pt[0], pt[1]), oy(pt[0], pt[1])) else p.lineTo(ox(pt[0], pt[1]), oy(pt[0], pt[1])) }
            p.close()
            return p
        }

        fun arcPath(l: Float, t: Float, rr: Float, b: Float, xs: Float, ys: Float, xe: Float, ye: Float, kind: Int, ccw: Boolean = true): Path {
            val cx = (l + rr) / 2f
            val cy = (t + b) / 2f
            val rx = abs(rr - l) / 2f
            val ry = abs(b - t) / 2f
            var a0 = atan2((ys - cy).toDouble() * rx, (xs - cx).toDouble() * ry)
            var a1 = atan2((ye - cy).toDouble() * rx, (xe - cx).toDouble() * ry)
            val p = Path()
            val yUp = oy(0f, 1f) < oy(0f, 0f)
            val counter = ccw != yUp
            if (counter) {
                while (a1 >= a0) a1 -= 2 * Math.PI
            } else {
                while (a1 <= a0) a1 += 2 * Math.PI
            }
            if (abs(a1 - a0) < 1e-9) a1 = a0 + if (counter) -2 * Math.PI else 2 * Math.PI
            val n = max(8, (abs(a1 - a0) / (Math.PI / 32)).toInt())
            for (i in 0..n) {
                val a = a0 + (a1 - a0) * i / n
                val x = cx + (rx * cos(a)).toFloat()
                val y = cy + (ry * sin(a)).toFloat()
                if (i == 0) {
                    if (kind == 2) {
                        p.moveTo(ox(cx, cy), oy(cx, cy))
                        p.lineTo(ox(x, y), oy(x, y))
                    } else {
                        p.moveTo(ox(x, y), oy(x, y))
                    }
                } else {
                    p.lineTo(ox(x, y), oy(x, y))
                }
            }
            if (kind != 0) p.close()
            return p
        }

        fun typeface(f: GdiFont?): Typeface {
            val face = f?.face?.lowercase() ?: ""
            val fam = PptFonts.androidFamily(PptFont(f?.face ?: "", 0, f?.charset ?: 0))
            val bold = (f?.weight ?: 400) >= 600
            val italic = f?.italic ?: false
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            OfficeFonts.typeface(f?.face, bold, italic)?.let { return it }
            return if (face.isEmpty()) Typeface.create(Typeface.SANS_SERIF, style) else Typeface.create(fam, style)
        }

        fun drawText(x: Float, y: Float, text: String, dx: FloatArray?, opaqueRect: RectF?, clipRect: RectF?) {
            flushPendingMask()
            val f = dc.font
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = dc.textColor
            paint.typeface = typeface(f)
            val hRaw = f?.height ?: 12f
            val em = if (hRaw < 0) -hRaw else hRaw * 0.86f
            val size = max(scaleLenY(if (em == 0f) 12f else em), 0.5f)
            paint.textSize = size
            paint.isUnderlineText = f?.underline ?: false
            paint.isStrikeThruText = f?.strike ?: false
            val str = if (f != null && PptFonts.isSymbolFont(PptFont(f.face, 0, f.charset))) PptFonts.mapSymbols(text, PptFont(f.face, 0, f.charset)) else text
            if (opaqueRect != null) {
                canvas.drawRect(opaqueRect, Paint().apply { color = dc.bkColor })
            }
            var px = ox(x, y)
            var py = oy(x, y)
            if (dc.textAlign and 1 != 0) {
                px = ox(dc.curX, dc.curY)
                py = oy(dc.curX, dc.curY)
            }
            val advances = if (dx != null && dx.size >= str.length) FloatArray(str.length) { scaleLen(dx[it]) } else null
            val total = advances?.sum() ?: paint.measureText(str)
            val fm = paint.fontMetrics
            val hAlign = dc.textAlign and 6
            val vAlign = dc.textAlign and 24
            val esc = (f?.escapement ?: 0f) / 10f
            canvas.save()
            clipRect?.let { canvas.clipRect(it) }
            val yUp = oy(0f, 1f) < oy(0f, 0f)
            val angle = if (yUp) -esc else -esc
            if (angle != 0f) canvas.rotate(angle, px, py)
            val startX = when (hAlign) {
                6 -> px - total / 2f
                2 -> px - total
                else -> px
            }
            val baseY = when (vAlign) {
                24 -> py
                8 -> py - fm.descent
                else -> py - fm.ascent
            }
            if (dc.bkMode == 2 && opaqueRect == null && str.isNotBlank()) {
                canvas.drawRect(startX, baseY + fm.ascent, startX + total, baseY + fm.descent, Paint().apply { color = dc.bkColor })
            }
            if (advances != null) {
                var cx = startX
                for (i in str.indices) {
                    canvas.drawText(str, i, i + 1, cx, baseY, paint)
                    cx += advances[i]
                }
            } else {
                canvas.drawText(str, startX, baseY, paint)
            }
            canvas.restore()
            if (dc.textAlign and 1 != 0) {
                val adv = total / max(devScaleX, 1e-6f)
                dc.curX += adv
            }
        }

        fun dibBitmap(off: Int, bmiLen: Int, bitsOff: Int, bitsLen: Int, usage: Int): Bitmap? {
            lastDibBpp = 0
            if (off < 0 || bmiLen <= 0 || off + bmiLen > data.size) return null
            val headerSize = r.s32(off)
            if (headerSize == 12) return null
            if (off + 16 <= data.size) lastDibBpp = r.u16(off + 14)
            val bitsStart = if (bitsOff > 0) bitsOff else off + bmiLen
            val bitsEnd = if (bitsLen > 0) min(data.size, bitsStart + bitsLen) else data.size
            if (bitsStart > data.size) return null
            val bmp = ByteArray(14 + bmiLen + (bitsEnd - bitsStart))
            bmp[0] = 'B'.code.toByte(); bmp[1] = 'M'.code.toByte()
            fun put(p: Int, v: Int) { bmp[p] = v.toByte(); bmp[p + 1] = (v shr 8).toByte(); bmp[p + 2] = (v shr 16).toByte(); bmp[p + 3] = (v shr 24).toByte() }
            put(2, bmp.size)
            put(10, 14 + bmiLen)
            System.arraycopy(data, off, bmp, 14, bmiLen)
            System.arraycopy(data, bitsStart, bmp, 14 + bmiLen, bitsEnd - bitsStart)
            val opts = BitmapFactory.Options()
            val w = r.s32(off + 4)
            val h = abs(r.s32(off + 8))
            var sample = 1
            while (w / sample > 2048 || h / sample > 2048) sample *= 2
            opts.inSampleSize = sample
            return BitmapFactory.decodeByteArray(bmp, 0, bmp.size, opts)
        }

        fun blit(bmp: Bitmap?, dx: Float, dy: Float, dw: Float, dh: Float, sx: Float, sy: Float, sw: Float, sh: Float, rop: Int) {
            val l = ox(dx, dy)
            val t = oy(dx, dy)
            val rr = ox(dx + dw, dy + dh)
            val b = oy(dx + dw, dy + dh)
            val dst = RectF(min(l, rr), min(t, b), max(l, rr), max(t, b))
            if (bmp == null) {
                flushPendingMask()
                val paint = when (rop) {
                    0x00000042 -> Paint().apply { color = Color.BLACK }
                    0x00FF0062 -> Paint().apply { color = Color.WHITE }
                    0x00F00021, 0x005A0049, 0x00FB0A09 -> brushPaint()
                    else -> null
                } ?: return
                canvas.drawRect(dst, paint)
                return
            }
            val scaleSrc = bmp.width.toFloat()
            val srcRect = android.graphics.Rect(
                sx.toInt().coerceIn(0, bmp.width), sy.toInt().coerceIn(0, bmp.height),
                (sx + abs(sw)).toInt().coerceIn(0, bmp.width), (sy + abs(sh)).toInt().coerceIn(0, bmp.height)
            )
            if (scaleSrc <= 0f || srcRect.width() <= 0 || srcRect.height() <= 0) return
            val flipX = l > rr
            val flipY = t > b
            val pm = pendingMask
            if (pm != null &&
                abs(pm.dst.left - dst.left) <= 1.5f && abs(pm.dst.top - dst.top) <= 1.5f &&
                abs(pm.dst.right - dst.right) <= 1.5f && abs(pm.dst.bottom - dst.bottom) <= 1.5f &&
                ((pm.rop == 0x00EE0086 && rop == 0x008800C6) || (pm.rop == 0x008800C6 && rop == 0x00EE0086))
            ) {
                pendingMask = null
                val composed = composeMaskedBitmap(pm.bmp, pm.src, bmp, srcRect, pm.rop == 0x00EE0086)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.save()
                if (flipX) canvas.scale(-1f, 1f, dst.centerX(), dst.centerY())
                if (flipY) canvas.scale(1f, -1f, dst.centerX(), dst.centerY())
                canvas.drawBitmap(composed, null, dst, paint)
                canvas.restore()
                return
            }
            flushPendingMask()
            if (lastDibBpp == 1 && (rop == 0x00EE0086 || rop == 0x008800C6)) {
                pendingMask = PendingMask(bmp, srcRect, dst, flipX, flipY, rop)
                return
            }
            val drawBmp = when (rop) {
                0x00EE0086 -> neutralizeMaskBitmap(bmp, srcRect, true)
                0x008800C6 -> neutralizeMaskBitmap(bmp, srcRect, false)
                else -> null
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            when (rop) {
                0x008800C6 -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                0x00EE0086 -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                0x00660046 -> paint.blendMode = android.graphics.BlendMode.DIFFERENCE
                0x00BB0226 -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            canvas.save()
            if (flipX) canvas.scale(-1f, 1f, dst.centerX(), dst.centerY())
            if (flipY) canvas.scale(1f, -1f, dst.centerX(), dst.centerY())
            if (drawBmp != null) canvas.drawBitmap(drawBmp, null, dst, paint) else canvas.drawBitmap(bmp, srcRect, dst, paint)
            canvas.restore()
        }

        fun clipToRect(l: Float, t: Float, rr: Float, b: Float, exclude: Boolean) {
            flushPendingMask()
            val p = rectPath(l, t, rr, b)
            if (exclude) {
                if (android.os.Build.VERSION.SDK_INT >= 26) canvas.clipOutPath(p)
            } else {
                canvas.clipPath(p)
            }
        }

        fun saveDc() {
            flushPendingMask()
            stack.add(dc.copy())
            canvas.save()
        }

        fun restoreDc(rel: Int) {
            flushPendingMask()
            var n = if (rel < 0) -rel else stack.size - rel + 1
            while (n-- > 0 && stack.isNotEmpty()) {
                dc = stack.removeAt(stack.size - 1)
                canvas.restore()
            }
        }

        fun resetClip() {
            flushPendingMask()
            canvas.restoreToCount(baseCount)
            baseCount = canvas.save()
            for (i in stack.indices) canvas.save()
        }
    }

    private class Wmf(data: ByteArray) : Player(data) {
        val objects = HashMap<Int, Any>()
        var numObjects = 0

        fun alloc(o: Any) {
            var i = 0
            while (objects.containsKey(i)) i++
            objects[i] = o
        }

        fun play(): MetafileImage? {
            var p = 0
            var bbox: RectF? = null
            if (r.s32(0) == 0x9AC6CDD7.toInt()) {
                bbox = RectF(r.s16(6).toFloat(), r.s16(8).toFloat(), r.s16(10).toFloat(), r.s16(12).toFloat())
                p = 22
            }
            numObjects = r.u16(p + 10)
            p += r.u16(p + 2) * 2
            val start = p
            var frame = bbox
            if (frame == null || frame.width() == 0f || frame.height() == 0f) {
                var q = start
                var ox = 0f; var oy = 0f; var ex = 0f; var ey = 0f
                var n = 0
                while (q + 6 <= data.size && n++ < MAX_RECORDS) {
                    val size = r.s32(q).toLong() * 2
                    val fn = r.u16(q + 4)
                    if (size < 6 || q + size > data.size || fn == 0) break
                    if (fn == 0x020B) { oy = r.s16(q + 6).toFloat(); ox = r.s16(q + 8).toFloat() }
                    if (fn == 0x020C && ex == 0f) { ey = r.s16(q + 6).toFloat(); ex = r.s16(q + 8).toFloat() }
                    q += size.toInt()
                }
                if (ex == 0f || ey == 0f) return null
                frame = RectF(ox, oy, ox + ex, oy + ey)
            }
            val fw = abs(frame.width())
            val fh = abs(frame.height())
            if (fw <= 0f || fh <= 0f) return null
            val s = min(1f, MAX_DIM / max(fw, fh)).let { if (max(fw, fh) < 256) 1024f / max(fw, fh) else it }
            val w = max(1, (fw * s).toInt())
            val h = max(1, (fh * s).toInt())
            val pic = Picture()
            begin(pic, w, h)
            dc.mapMode = 8
            dc.winOrgX = min(frame.left, frame.right); dc.winOrgY = min(frame.top, frame.bottom)
            dc.winExtX = fw; dc.winExtY = fh
            dc.vpOrgX = 0f; dc.vpOrgY = 0f; dc.vpExtX = fw; dc.vpExtY = fh
            devScaleX = w / fw
            devScaleY = h / fh
            dc.pen = GdiPen(0, 0f, Color.BLACK)
            dc.brush = GdiBrush(0, Color.WHITE, 0, null)
            p = start
            var n = 0
            while (p + 6 <= data.size && n++ < MAX_RECORDS) {
                val size = r.s32(p).toLong() * 2
                val fn = r.u16(p + 4)
                if (size < 6 || p + size > data.size) break
                if (fn == 0) break
                try {
                    record(fn, p + 6, (p + size).toInt())
                } catch (_: Throwable) {
                }
                p += size.toInt()
            }
            flushPendingMask()
            while (stack.isNotEmpty()) restoreDc(-1)
            pic.endRecording()
            return MetafileImage(pic, w, h)
        }

        fun s(i: Int, base: Int) = r.s16(base + i * 2).toFloat()

        fun color(p: Int) = Color.rgb(r.u8(p), r.u8(p + 1), r.u8(p + 2))

        fun record(fn: Int, a: Int, end: Int) {
            when (fn) {
                0x0103 -> { }
                0x020B -> { dc.winOrgY = s(0, a); dc.winOrgX = s(1, a) }
                0x020C -> { dc.winExtY = s(0, a); dc.winExtX = s(1, a) }
                0x020D -> { }
                0x020E -> { }
                0x0211 -> { dc.winOrgY += s(0, a); dc.winOrgX += s(1, a) }
                0x0410 -> {
                    val yd = s(0, a); val yn = s(1, a); val xd = s(2, a); val xn = s(3, a)
                    if (xd != 0f) dc.winExtX = dc.winExtX * xn / xd
                    if (yd != 0f) dc.winExtY = dc.winExtY * yn / yd
                }
                0x0201 -> dc.bkColor = color(a)
                0x0102 -> dc.bkMode = r.u16(a)
                0x0209 -> dc.textColor = color(a)
                0x012E -> dc.textAlign = r.u16(a)
                0x0104 -> dc.rop2 = r.u16(a)
                0x0106 -> dc.polyFill = r.u16(a)
                0x02FA -> alloc(GdiPen(r.u16(a), r.s16(a + 2).toFloat(), color(a + 6)))
                0x02FC -> {
                    val style = r.u16(a)
                    alloc(GdiBrush(style, color(a + 2), r.u16(a + 6), null))
                }
                0x0142 -> {
                    val style = r.u16(a)
                    val bmi = a + 4
                    val bmp = dibBitmapInline(bmi, end)
                    alloc(GdiBrush(if (bmp != null) 5 else 0, if (style == 3) Color.BLACK else Color.GRAY, 0, bmp))
                }
                0x01F9 -> alloc(GdiBrush(0, Color.GRAY, 0, null))
                0x02FB -> {
                    val face = String(data, a + 18, min(32, end - (a + 18)).coerceAtLeast(0), Charsets.ISO_8859_1).substringBefore('\u0000')
                    alloc(GdiFont(
                        r.s16(a).toFloat(), r.s16(a + 4).toFloat(), r.s16(a + 8), r.u8(a + 10) != 0,
                        r.u8(a + 11) != 0, r.u8(a + 12) != 0, r.u8(a + 13), face
                    ))
                }
                0x00F7, 0x06FF -> alloc(Any())
                0x012D -> {
                    when (val o = objects[r.u16(a)]) {
                        is GdiPen -> dc.pen = o
                        is GdiBrush -> dc.brush = o
                        is GdiFont -> dc.font = o
                    }
                }
                0x01F0 -> objects.remove(r.u16(a))
                0x0214 -> { dc.curY = s(0, a); dc.curX = s(1, a) }
                0x0213 -> {
                    val y = s(0, a); val x = s(1, a)
                    val pth = Path()
                    pth.moveTo(ox(dc.curX, dc.curY), oy(dc.curX, dc.curY))
                    pth.lineTo(ox(x, y), oy(x, y))
                    drawShape(pth, false, true)
                    dc.curX = x; dc.curY = y
                }
                0x0325, 0x0324 -> {
                    val n = r.s16(a).coerceIn(0, (end - a - 2) / 4)
                    val xs = FloatArray(n) { s(1 + it * 2, a) }
                    val ys = FloatArray(n) { s(2 + it * 2, a) }
                    if (fn == 0x0324) drawShape(polyPath(xs, ys, true), true, true) else drawShape(polyPath(xs, ys, false), false, true)
                }
                0x0538 -> {
                    val polys = r.u16(a)
                    var q = a + 2 + polys * 2
                    val pth = Path()
                    for (i in 0 until polys) {
                        val n = r.u16(a + 2 + i * 2)
                        if (q + n * 4 > end) break
                        val xs = FloatArray(n) { r.s16(q + it * 4).toFloat() }
                        val ys = FloatArray(n) { r.s16(q + it * 4 + 2).toFloat() }
                        pth.addPath(polyPath(xs, ys, true))
                        q += n * 4
                    }
                    drawShape(pth, true, true)
                }
                0x041B -> drawShape(rectPath(s(3, a), s(2, a), s(1, a), s(0, a)), true, true)
                0x061C -> drawShape(roundRectPath(s(5, a), s(4, a), s(3, a), s(2, a), s(1, a), s(0, a)), true, true)
                0x0418 -> drawShape(ellipsePath(s(3, a), s(2, a), s(1, a), s(0, a)), true, true)
                0x0817, 0x081A, 0x0830 -> {
                    val ye = s(0, a); val xe = s(1, a); val ys = s(2, a); val xs = s(3, a)
                    val b = s(4, a); val rr = s(5, a); val t = s(6, a); val l = s(7, a)
                    val kind = when (fn) { 0x0817 -> 0; 0x081A -> 2; else -> 1 }
                    drawShape(arcPath(l, t, rr, b, xs, ys, xe, ye, kind), kind != 0, true)
                }
                0x0521 -> {
                    val len = r.s16(a)
                    val strBytes = (len + 1) and -2
                    val y = r.s16(a + 2 + strBytes).toFloat()
                    val x = r.s16(a + 4 + strBytes).toFloat()
                    drawText(x, y, decodeAnsi(a + 2, len), null, null, null)
                }
                0x0A32 -> {
                    val y = s(0, a); val x = s(1, a)
                    val len = r.s16(a + 4)
                    val opts = r.u16(a + 6)
                    var q = a + 8
                    var rect: RectF? = null
                    if (opts and 0x6 != 0 && q + 8 <= end) {
                        val l = r.s16(q).toFloat(); val t = r.s16(q + 2).toFloat(); val rr = r.s16(q + 4).toFloat(); val b = r.s16(q + 6).toFloat()
                        rect = RectF(min(ox(l, t), ox(rr, b)), min(oy(l, t), oy(rr, b)), max(ox(l, t), ox(rr, b)), max(oy(l, t), oy(rr, b)))
                        q += 8
                    }
                    if (len <= 0 || q + len > end) {
                        if (rect != null && opts and 0x2 != 0) canvas.drawRect(rect, Paint().apply { color = dc.bkColor })
                        return
                    }
                    val text = decodeAnsi(q, len)
                    val dxStart = q + ((len + 1) and -2)
                    val dx = if (dxStart + len * 2 <= end) FloatArray(len) { r.s16(dxStart + it * 2).toFloat() } else null
                    val dxText = if (dx != null && text.length != len) null else dx
                    drawText(x, y, text, dxText, if (opts and 0x2 != 0) rect else null, if (opts and 0x4 != 0) rect else null)
                }
                0x0F43 -> {
                    val rop = r.s32(a)
                    val sh = s(3, a); val sw = s(4, a); val sy = s(5, a); val sx = s(6, a)
                    val dh = s(7, a); val dw = s(8, a); val dy = s(9, a); val dx = s(10, a)
                    val bmp = dibBitmapInline(a + 22, end)
                    blit(bmp, dx, dy, dw, dh, sx, flipSrcY(bmp, sy, sh), sw, sh, rop)
                }
                0x0B41 -> {
                    val rop = r.s32(a)
                    val hasBitmap = (end - a) > 22
                    if (hasBitmap) {
                        val sh = s(2, a); val sw = s(3, a); val sy = s(4, a); val sx = s(5, a)
                        val dh = s(6, a); val dw = s(7, a); val dy = s(8, a); val dx = s(9, a)
                        val bmp = dibBitmapInline(a + 20, end)
                        blit(bmp, dx, dy, dw, dh, sx, flipSrcY(bmp, sy, sh), sw, sh, rop)
                    } else {
                        val dh = s(7, a); val dw = s(8, a); val dy = s(9, a); val dx = s(10, a)
                        blit(null, dx, dy, dw, dh, 0f, 0f, 0f, 0f, rop)
                    }
                }
                0x0940 -> {
                    val rop = r.s32(a)
                    val hasBitmap = (end - a) > 18
                    if (hasBitmap) {
                        val sy = s(2, a); val sx = s(3, a); val h = s(4, a); val w = s(5, a); val dy = s(6, a); val dx = s(7, a)
                        val bmp = dibBitmapInline(a + 16, end)
                        blit(bmp, dx, dy, w, h, sx, flipSrcY(bmp, sy, h), w, h, rop)
                    } else {
                        val h = s(5, a); val w = s(6, a); val dy = s(7, a); val dx = s(8, a)
                        blit(null, dx, dy, w, h, 0f, 0f, 0f, 0f, rop)
                    }
                }
                0x061D -> {
                    val rop = r.s32(a)
                    val h = s(2, a); val w = s(3, a); val y = s(4, a); val x = s(5, a)
                    blit(null, x, y, w, h, 0f, 0f, 0f, 0f, rop)
                }
                0x001E -> saveDc()
                0x0127 -> restoreDc(r.s16(a))
                0x0416 -> clipToRect(s(3, a), s(2, a), s(1, a), s(0, a), false)
                0x0415 -> clipToRect(s(3, a), s(2, a), s(1, a), s(0, a), true)
                0x012C -> if (objects[r.u16(a)] == null) resetClip()
            }
        }

        fun flipSrcY(bmp: Bitmap?, sy: Float, sh: Float): Float = if (bmp == null) sy else sy

        fun dibBitmapInline(off: Int, end: Int): Bitmap? {
            if (off + 40 > end) return null
            val hs = r.s32(off)
            if (hs < 12 || off + hs > end) return null
            val bitCount = r.u16(off + 14)
            val compression = r.s32(off + 16)
            val clrUsed = r.s32(off + 32)
            val palette = when {
                clrUsed > 0 -> clrUsed
                bitCount <= 8 -> 1 shl bitCount
                else -> 0
            }
            val masks = if (hs == 40 && (compression == 3 || compression == 6)) 12 else 0
            val bmiLen = hs + masks + palette * 4
            if (off + bmiLen > end) return null
            return dibBitmap(off, bmiLen, off + bmiLen, end - off - bmiLen, 0)
        }

        fun decodeAnsi(off: Int, len: Int): String {
            val f = dc.font
            val cs = charsetFor(f?.charset ?: 0)
            val n = len.coerceIn(0, data.size - off)
            return try { String(data, off, n, cs) } catch (_: Throwable) { String(data, off, n, Charsets.ISO_8859_1) }
        }
    }

    private class Emf(data: ByteArray) : Player(data) {
        val objects = HashMap<Int, Any>()

        fun play(): MetafileImage? {
            val bl = r.s32(8).toFloat(); val bt = r.s32(12).toFloat(); val br = r.s32(16).toFloat(); val bb = r.s32(20).toFloat()
            val fl = r.s32(24).toFloat(); val ft = r.s32(28).toFloat(); val fr = r.s32(32).toFloat(); val fb = r.s32(36).toFloat()
            val devW = r.s32(72).toFloat(); val devH = r.s32(76).toFloat()
            val mmW = r.s32(80).toFloat(); val mmH = r.s32(84).toFloat()
            val pxPerMmX = if (mmW > 0f && devW > 0f) devW / mmW else 3.78f
            val pxPerMmY = if (mmH > 0f && devH > 0f) devH / mmH else 3.78f
            var left = fl / 100f * pxPerMmX
            var top = ft / 100f * pxPerMmY
            var right = fr / 100f * pxPerMmX
            var bottom = fb / 100f * pxPerMmY
            if (right - left <= 0f || bottom - top <= 0f) {
                left = bl; top = bt; right = br + 1; bottom = bb + 1
            }
            val fw = right - left
            val fh = bottom - top
            if (fw <= 0f || fh <= 0f) return null
            val s = if (max(fw, fh) < 512) 1024f / max(fw, fh) else min(1f, MAX_DIM / max(fw, fh))
            val w = max(1, (fw * s).toInt())
            val h = max(1, (fh * s).toInt())
            val pic = Picture()
            begin(pic, w, h)
            devScaleX = w / fw
            devScaleY = h / fh
            devOffX = -left * devScaleX
            devOffY = -top * devScaleY
            fixedUnitsPerDevX = 1f
            fixedUnitsPerDevY = 1f
            val unitsPerMm = floatArrayOf(1f, 1f, 10f, 100f, 1f / 0.254f, 1f / 0.0254f, 1440f / 25.4f)
            dc.pen = GdiPen(0, 0f, Color.BLACK)
            dc.brush = GdiBrush(0, Color.WHITE, 0, null)
            var p = 0
            var n = 0
            while (p + 8 <= data.size && n++ < MAX_RECORDS) {
                val type = r.s32(p)
                val size = r.s32(p + 4)
                if (size < 8 || p + size > data.size) break
                if (type == 14) break
                try {
                    if (type == 17) {
                        dc.mapMode = r.s32(p + 8)
                        val m = dc.mapMode
                        if (m in 2..6) {
                            val u = unitsPerMm[m]
                            fixedUnitsPerDevX = u / pxPerMmX
                            fixedUnitsPerDevY = u / pxPerMmY
                        }
                    } else {
                        record(type, p + 8, p + size, p)
                    }
                } catch (_: Throwable) {
                }
                p += size
            }
            flushPendingMask()
            while (stack.isNotEmpty()) restoreDc(-1)
            pic.endRecording()
            return MetafileImage(pic, w, h)
        }

        fun i(off: Int) = r.s32(off).toFloat()
        fun color(p: Int) = Color.rgb(r.u8(p), r.u8(p + 1), r.u8(p + 2))

        fun stock(idx: Int): Any? = when (idx and 0x7FFFFFFF) {
            0 -> GdiBrush(0, Color.WHITE, 0, null)
            1 -> GdiBrush(0, Color.rgb(192, 192, 192), 0, null)
            2 -> GdiBrush(0, Color.rgb(128, 128, 128), 0, null)
            3 -> GdiBrush(0, Color.rgb(64, 64, 64), 0, null)
            4 -> GdiBrush(0, Color.BLACK, 0, null)
            5 -> GdiBrush(1, 0, 0, null)
            6 -> GdiPen(0, 0f, Color.WHITE)
            7 -> GdiPen(0, 0f, Color.BLACK)
            8 -> GdiPen(5, 0f, 0)
            10, 11, 12, 13, 14, 16, 17 -> GdiFont(-12f, 0f, 400, false, false, false, 0, "")
            18 -> GdiBrush(0, Color.WHITE, 0, null)
            19 -> GdiPen(0, 0f, Color.BLACK)
            else -> null
        }

        fun points32(a: Int, end: Int, count: Int): Pair<FloatArray, FloatArray> {
            val n = count.coerceIn(0, (end - a) / 8)
            return FloatArray(n) { i(a + it * 8) } to FloatArray(n) { i(a + it * 8 + 4) }
        }

        fun points16(a: Int, end: Int, count: Int): Pair<FloatArray, FloatArray> {
            val n = count.coerceIn(0, (end - a) / 4)
            return FloatArray(n) { r.s16(a + it * 4).toFloat() } to FloatArray(n) { r.s16(a + it * 4 + 2).toFloat() }
        }

        fun bezierPath(xs: FloatArray, ys: FloatArray, fromCurrent: Boolean): Path {
            val p = Path()
            var k = 0
            if (fromCurrent) {
                p.moveTo(ox(dc.curX, dc.curY), oy(dc.curX, dc.curY))
            } else if (xs.isNotEmpty()) {
                p.moveTo(ox(xs[0], ys[0]), oy(xs[0], ys[0]))
                k = 1
            }
            while (k + 2 < xs.size) {
                p.cubicTo(ox(xs[k], ys[k]), oy(xs[k], ys[k]), ox(xs[k + 1], ys[k + 1]), oy(xs[k + 1], ys[k + 1]), ox(xs[k + 2], ys[k + 2]), oy(xs[k + 2], ys[k + 2]))
                k += 3
            }
            if (xs.isNotEmpty()) { dc.curX = xs.last(); dc.curY = ys.last() }
            return p
        }

        fun lineToPath(xs: FloatArray, ys: FloatArray): Path {
            val p = Path()
            p.moveTo(ox(dc.curX, dc.curY), oy(dc.curX, dc.curY))
            for (k in xs.indices) p.lineTo(ox(xs[k], ys[k]), oy(xs[k], ys[k]))
            if (xs.isNotEmpty()) { dc.curX = xs.last(); dc.curY = ys.last() }
            return p
        }

        fun emitInPath(p: Path) {
            val cur = path
            if (cur != null) cur.addPath(p) else drawShape(p, false, true)
        }

        fun record(type: Int, a: Int, end: Int, recStart: Int) {
            when (type) {
                9 -> { dc.winExtX = i(a); dc.winExtY = i(a + 4) }
                10 -> { dc.winOrgX = i(a); dc.winOrgY = i(a + 4) }
                11 -> { dc.vpExtX = i(a); dc.vpExtY = i(a + 4) }
                12 -> { dc.vpOrgX = i(a); dc.vpOrgY = i(a + 4) }
                18 -> dc.bkMode = r.s32(a)
                19 -> dc.polyFill = r.s32(a)
                20 -> dc.rop2 = r.s32(a)
                22 -> dc.textAlign = r.s32(a)
                24 -> dc.textColor = color(a)
                25 -> dc.bkColor = color(a)
                27 -> { dc.curX = i(a); dc.curY = i(a + 4); path?.moveTo(ox(dc.curX, dc.curY), oy(dc.curX, dc.curY)) }
                29 -> clipToRect(i(a), i(a + 4), i(a + 8), i(a + 12), true)
                30 -> clipToRect(i(a), i(a + 4), i(a + 8), i(a + 12), false)
                33 -> saveDc()
                34 -> restoreDc(r.s32(a))
                35 -> dc.world = xform(a)
                36 -> {
                    val m = xform(a)
                    when (r.s32(a + 24)) {
                        1 -> dc.world = Matrix()
                        2 -> dc.world.preConcat(m)
                        3 -> dc.world.postConcat(m)
                        4 -> dc.world = m
                    }
                }
                37 -> {
                    val idx = r.s32(a)
                    val o = if (idx < 0) stock(idx) else objects[idx]
                    when (o) {
                        is GdiPen -> dc.pen = o
                        is GdiBrush -> dc.brush = o
                        is GdiFont -> dc.font = o
                    }
                }
                38 -> objects[r.s32(a)] = GdiPen(r.s32(a + 4), i(a + 8), color(a + 16))
                95 -> {
                    val style = r.s32(a + 20)
                    val brushStyle = r.s32(a + 28)
                    objects[r.s32(a)] = GdiPen(if (brushStyle == 1) 5 else style, i(a + 24), color(a + 32))
                }
                39 -> objects[r.s32(a)] = GdiBrush(r.s32(a + 4), color(a + 8), r.s32(a + 12), null)
                93, 94 -> {
                    val offBmi = r.s32(a + 8)
                    val cbBmi = r.s32(a + 12)
                    val offBits = r.s32(a + 16)
                    val cbBits = r.s32(a + 20)
                    val bmp = dibBitmap(recStart + offBmi, cbBmi, recStart + offBits, cbBits, r.s32(a + 4))
                    objects[r.s32(a)] = GdiBrush(if (bmp != null) 5 else 0, Color.GRAY, 0, bmp)
                }
                40 -> objects.remove(r.s32(a))
                82 -> {
                    val lf = a + 4
                    val faceBytes = min(64, end - (lf + 28)).coerceAtLeast(0)
                    val face = String(data, lf + 28, faceBytes, Charsets.UTF_16LE).substringBefore('\u0000')
                    objects[r.s32(a)] = GdiFont(
                        i(lf), i(lf + 8), r.s32(lf + 16), r.u8(lf + 20) != 0, r.u8(lf + 21) != 0,
                        r.u8(lf + 22) != 0, r.u8(lf + 23), face
                    )
                }
                2, 3, 4, 85, 86, 87 -> {
                    val count = r.s32(a + 16)
                    val (xs, ys) = if (type >= 85) points16(a + 20, end, count) else points32(a + 20, end, count)
                    when (type) {
                        2, 85 -> emitOrDraw(bezierPath(xs, ys, false), false)
                        3, 86 -> drawShape(polyPath(xs, ys, true), true, true)
                        else -> {
                            if (path != null) path!!.addPath(polyPath(xs, ys, false)) else drawShape(polyPath(xs, ys, false), false, true)
                        }
                    }
                }
                5, 88 -> {
                    val count = r.s32(a + 16)
                    val (xs, ys) = if (type == 88) points16(a + 20, end, count) else points32(a + 20, end, count)
                    emitOrDraw(bezierPath(xs, ys, true), false)
                }
                6, 89 -> {
                    val count = r.s32(a + 16)
                    val (xs, ys) = if (type == 89) points16(a + 20, end, count) else points32(a + 20, end, count)
                    emitOrDraw(lineToPath(xs, ys), false)
                }
                7, 8, 90, 91 -> {
                    val polys = r.s32(a + 16)
                    val total = r.s32(a + 20)
                    if (polys !in 0..1_000_000) return
                    var q = a + 24 + polys * 4
                    val pth = Path()
                    val small = type >= 90
                    for (k in 0 until polys) {
                        val c = r.s32(a + 24 + k * 4)
                        val (xs, ys) = if (small) points16(q, end, c) else points32(q, end, c)
                        pth.addPath(polyPath(xs, ys, type == 8 || type == 91))
                        q += c * (if (small) 4 else 8)
                    }
                    if (type == 8 || type == 91) drawShape(pth, true, true) else drawShape(pth, false, true)
                    if (total < 0) return
                }
                42 -> drawShape(ellipsePath(i(a), i(a + 4), i(a + 8), i(a + 12)), true, true)
                43 -> drawShape(rectPath(i(a), i(a + 4), i(a + 8), i(a + 12)), true, true)
                44 -> drawShape(roundRectPath(i(a), i(a + 4), i(a + 8), i(a + 12), i(a + 16), i(a + 20)), true, true)
                45, 46, 47, 55 -> {
                    val kind = when (type) { 45, 55 -> 0; 46 -> 1; else -> 2 }
                    val pth = arcPath(i(a), i(a + 4), i(a + 8), i(a + 12), i(a + 16), i(a + 20), i(a + 24), i(a + 28), kind)
                    if (type == 55) {
                        emitOrDraw(pth, false)
                        dc.curX = i(a + 24); dc.curY = i(a + 28)
                    } else {
                        drawShape(pth, kind != 0, true)
                    }
                }
                54 -> {
                    val x = i(a); val y = i(a + 4)
                    val pth = Path()
                    pth.moveTo(ox(dc.curX, dc.curY), oy(dc.curX, dc.curY))
                    pth.lineTo(ox(x, y), oy(x, y))
                    emitOrDraw(pth, false)
                    dc.curX = x; dc.curY = y
                }
                59 -> path = Path()
                60 -> { }
                61 -> path?.close()
                62, 63, 64 -> {
                    val pth = path ?: return
                    path = null
                    pth.fillType = fillType()
                    if (type != 64) brushPaint()?.let { canvas.drawPath(pth, it) }
                    if (type != 62) penPaint()?.let { canvas.drawPath(pth, it) }
                }
                67 -> {
                    val pth = path ?: return
                    path = null
                    pth.fillType = fillType()
                    canvas.clipPath(pth)
                }
                68 -> path = null
                75 -> {
                    val cb = r.s32(a)
                    val mode = r.s32(a + 4)
                    if (cb == 0 || mode == 5 && cb < 32) {
                        if (mode == 5) resetClip()
                        return
                    }
                    val rd = a + 8
                    val count = r.s32(rd + 8)
                    val region = Path()
                    for (k in 0 until count.coerceIn(0, 100_000)) {
                        val q = rd + 32 + k * 16
                        if (q + 16 > end) break
                        val l = r.s32(q).toFloat(); val t = r.s32(q + 4).toFloat(); val rr = r.s32(q + 8).toFloat(); val b = r.s32(q + 12).toFloat()
                        region.addRect(l * devScaleX + devOffX, t * devScaleY + devOffY, rr * devScaleX + devOffX, b * devScaleY + devOffY, Path.Direction.CW)
                    }
                    when (mode) {
                        5 -> { resetClip(); canvas.clipPath(region) }
                        1 -> canvas.clipPath(region)
                        4 -> if (android.os.Build.VERSION.SDK_INT >= 26) canvas.clipOutPath(region)
                    }
                }
                83, 84 -> {
                    val et = a + 28
                    val refX = i(et)
                    val refY = i(et + 4)
                    val nChars = r.s32(et + 8)
                    val offString = r.s32(et + 12)
                    val opts = r.s32(et + 16)
                    val rl = i(et + 20); val rt = i(et + 24); val rr = i(et + 28); val rb = i(et + 32)
                    val offDx = r.s32(et + 36)
                    val sOff = recStart + offString
                    if (nChars !in 0..100_000) return
                    val text = if (type == 84) {
                        val len = min(nChars * 2, data.size - sOff).coerceAtLeast(0)
                        String(data, sOff, len and -2, Charsets.UTF_16LE)
                    } else {
                        val len = min(nChars, data.size - sOff).coerceAtLeast(0)
                        String(data, sOff, len, charsetFor(dc.font?.charset ?: 0))
                    }
                    val dxOff = recStart + offDx
                    val pdy = opts and 0x2000 != 0
                    val dx = if (offDx > 0 && dxOff + nChars * (if (pdy) 8 else 4) <= data.size) {
                        FloatArray(nChars) { r.s32(dxOff + it * (if (pdy) 8 else 4)).toFloat() }
                    } else null
                    val rect = if (rr > rl && rb > rt) RectF(ox(rl, rt), oy(rl, rt), ox(rr, rb), oy(rr, rb)).also { it.sort() } else null
                    drawText(refX, refY, text, if (dx != null && text.length == nChars) dx else null, if (opts and 0x2 != 0) rect else null, if (opts and 0x4 != 0) rect else null)
                }
                76, 77 -> {
                    val dx = i(a + 16); val dy = i(a + 20); val cx = i(a + 24); val cy = i(a + 28)
                    val rop = r.s32(a + 32)
                    val sx = i(a + 36); val sy = i(a + 40)
                    val offBmi = r.s32(a + 76); val cbBmi = r.s32(a + 80); val offBits = r.s32(a + 84); val cbBits = r.s32(a + 88)
                    val sw = if (type == 77) i(a + 92) else cx
                    val sh = if (type == 77) i(a + 96) else cy
                    val bmp = if (cbBmi > 0) dibBitmap(recStart + offBmi, cbBmi, recStart + offBits, cbBits, r.s32(a + 72)) else null
                    blit(bmp, dx, dy, cx, cy, sx, sy, sw, sh, rop)
                }
                81 -> {
                    val dx = i(a + 16); val dy = i(a + 20); val sx = i(a + 24); val sy = i(a + 28)
                    val sw = i(a + 32); val sh = i(a + 36)
                    val offBmi = r.s32(a + 40); val cbBmi = r.s32(a + 44); val offBits = r.s32(a + 48); val cbBits = r.s32(a + 52)
                    val rop = r.s32(a + 60)
                    val cx = i(a + 64); val cy = i(a + 68)
                    val bmp = dibBitmap(recStart + offBmi, cbBmi, recStart + offBits, cbBits, r.s32(a + 56))
                    blit(bmp, dx, dy, cx, cy, sx, srcY(bmp, recStart + offBmi, sy, sh), sw, sh, rop)
                }
                80 -> {
                    val dx = i(a + 16); val dy = i(a + 20); val sx = i(a + 24); val sy = i(a + 28)
                    val cx = i(a + 32); val cy = i(a + 36)
                    val offBmi = r.s32(a + 40); val cbBmi = r.s32(a + 44); val offBits = r.s32(a + 48); val cbBits = r.s32(a + 52)
                    val bmp = dibBitmap(recStart + offBmi, cbBmi, recStart + offBits, cbBits, r.s32(a + 56))
                    blit(bmp, dx, dy, cx, cy, sx, srcY(bmp, recStart + offBmi, sy, cy), cx, cy, 0x00CC0020)
                }
            }
        }

        fun srcY(bmp: Bitmap?, bmi: Int, sy: Float, sh: Float): Float {
            if (bmp == null) return sy
            val h = r.s32(bmi + 8)
            return if (h > 0) bmp.height - sy - abs(sh) else sy
        }

        fun emitOrDraw(p: Path, fill: Boolean) {
            val cur = path
            if (cur != null) cur.addPath(p) else drawShape(p, fill, true)
        }

        fun xform(a: Int): Matrix {
            val m11 = java.lang.Float.intBitsToFloat(r.s32(a))
            val m12 = java.lang.Float.intBitsToFloat(r.s32(a + 4))
            val m21 = java.lang.Float.intBitsToFloat(r.s32(a + 8))
            val m22 = java.lang.Float.intBitsToFloat(r.s32(a + 12))
            val dx = java.lang.Float.intBitsToFloat(r.s32(a + 16))
            val dy = java.lang.Float.intBitsToFloat(r.s32(a + 20))
            return Matrix().apply { setValues(floatArrayOf(m11, m21, dx, m12, m22, dy, 0f, 0f, 1f)) }
        }
    }

    fun charsetFor(cs: Int): Charset {
        val name = when (cs) {
            204 -> "windows-1251"
            238 -> "windows-1250"
            161 -> "windows-1253"
            162 -> "windows-1254"
            177 -> "windows-1255"
            178 -> "windows-1256"
            186 -> "windows-1257"
            163 -> "windows-1258"
            222 -> "x-windows-874"
            128 -> "Shift_JIS"
            129 -> "x-windows-949"
            134 -> "GBK"
            136 -> "Big5"
            2 -> "ISO-8859-1"
            else -> "windows-1252"
        }
        return try { Charset.forName(name) } catch (_: Throwable) { Charsets.ISO_8859_1 }
    }
}
