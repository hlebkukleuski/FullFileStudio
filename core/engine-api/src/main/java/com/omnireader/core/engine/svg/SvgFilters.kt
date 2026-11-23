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
 * Portions ported and adapted from resvg, usvg, svgtypes, simplecss, unicode-vo,
 * fontdb, tiny-skia, and kurbo (https://github.com/linebender/resvg),
 * Copyright (c) Yevhenii Reizner, The resvg Authors, and The Linebender Authors,
 * licensed under MPL-2.0 / Apache-2.0 / MIT (tiny-skia under BSD-3-Clause, Copyright (c) 2011 Google Inc. and
 * Copyright (c) 2020 Yevhenii Reizner; IIR Gaussian blur based on Copyright (c) 2011 Pascal Getreuer, BSD-2-Clause;
 * box blur based on fastblur, Copyright (c) Felix Schütt and Ivan Kutskir, MIT;
 * feTurbulence based on the W3C SVG 1.1 Filter Effects reference implementation).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package com.omnireader.core.engine.svg

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.sqrt

internal object SvgFilters {
    private class FilterError : Exception() { override fun fillInStackTrace(): Throwable = this }

    private class Img(val w: Int, val h: Int, val data: ByteArray, val region: IRect, val linear: Boolean) {
        fun copy() = Img(w, h, data.copyOf(), region, linear)
        fun toSpace(linearTarget: Boolean): Img {
            if (linearTarget == linear) return this
            val d = data.copyOf()
            if (linearTarget) Px.toLinear(d) else Px.toSrgb(d)
            return Img(w, h, d, region, linearTarget)
        }
        fun bitmap(): Bitmap { val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); Px.put(b, data); return b }
    }

    private fun fromBitmap(b: Bitmap, linear: Boolean): Img { val d = Px.bytes(b); val r = Img(b.width, b.height, d, IRect(0, 0, b.width, b.height), linear); b.recycle(); return r }
    private fun empty(w: Int, h: Int, linear: Boolean) = Img(w, h, ByteArray(w * h * 4), IRect(0, 0, w, h), linear)

    fun apply(filter: RFilter, ts: Ts, source: Bitmap) {
        try {
            val (img, region) = applyInner(filter, ts, source)
            val out = img.toSpace(false)
            source.eraseColor(Color.TRANSPARENT)
            val b = out.bitmap()
            Canvas(source).drawBitmap(b, region.x.toFloat(), region.y.toFloat(), null)
            b.recycle()
        } catch (e: FilterError) {
            source.eraseColor(Color.TRANSPARENT)
        } catch (e: OutOfMemoryError) {
            source.eraseColor(Color.TRANSPARENT)
        }
    }

    private fun applyInner(filter: RFilter, tsIn: Ts, sourceBmp: Bitmap): Pair<Img, IRect> {
        val region0 = filter.rect.transform(tsIn)?.takeIf { it.nonZero }?.toIRect() ?: throw FilterError()
        val sourceRect = IRect(0, 0, sourceBmp.width, sourceBmp.height)
        val region = region0.fitTo(sourceRect) ?: throw FilterError()
        val full = Px.bytes(sourceBmp)
        val source = if (region != sourceRect) {
            val d = ByteArray(region.w * region.h * 4)
            for (y in 0 until region.h) System.arraycopy(full, ((region.y + y) * sourceBmp.width + region.x) * 4, d, y * region.w * 4, region.w * 4)
            d
        } else full
        val outputRegion = region
        val ts = tsIn.postTranslate(-region.x.toFloat(), -region.y.toFloat())
        val reg = IRect(0, 0, region.w, region.h)
        val results = ArrayList<Pair<String, Img>>()
        fun input(i: FeInput): Img = when (i) {
            FeInput.SourceGraphic -> Img(reg.w, reg.h, source.copyOf(), reg, false)
            FeInput.SourceAlpha -> { val d = source.copyOf(); var k = 0; while (k < d.size) { d[k] = 0; d[k + 1] = 0; d[k + 2] = 0; k += 4 }; Img(reg.w, reg.h, d, reg, false) }
            is FeInput.Ref -> results.lastOrNull { it.first == i.name }?.second?.let { Img(it.w, it.h, it.data, it.region, it.linear) } ?: input(FeInput.SourceGraphic)
        }
        for (p in filter.primitives) {
            val sub = p.rect.transform(ts)?.takeIf { it.nonZero }?.toIRect() ?: throw FilterError()
            val cs = p.linearRgb
            var result: Img = when (val k = p.kind) {
                is FeKind.Blend -> blend(k, cs, reg, input(k.in1), input(k.in2))
                is FeKind.DropShadow -> dropShadow(k, cs, ts, input(k.input))
                is FeKind.Flood -> flood(k, reg)
                is FeKind.GaussianBlur -> blur(k, cs, ts, input(k.input))
                is FeKind.Offset -> offset(k, ts, input(k.input))
                is FeKind.Composite -> composite(k, cs, reg, input(k.in1), input(k.in2))
                is FeKind.Merge -> merge(k, cs, reg, ::input)
                is FeKind.Tile -> tile(input(k.input), reg)
                is FeKind.Image -> image(k, reg, sub, ts)
                is FeKind.ComponentTransfer -> componentTransfer(k, cs, input(k.input))
                is FeKind.ColorMatrix -> colorMatrix(k, cs, input(k.input))
                is FeKind.ConvolveMatrix -> convolve(k, cs, input(k.input))
                is FeKind.Morphology -> morphology(k, cs, ts, input(k.input))
                is FeKind.DisplacementMap -> displacement(k, reg, cs, ts, input(k.in1), input(k.in2))
                is FeKind.Turbulence -> turbulence(k, reg, cs, ts)
                is FeKind.DiffuseLighting -> lighting(k.light, k.surfaceScale, k.color, reg, cs, ts, input(k.input), true) { n, l -> diffuseFactor(k, n, l) }
                is FeKind.SpecularLighting -> lighting(k.light, k.surfaceScale, k.color, reg, cs, ts, input(k.input), false) { n, l -> specularFactor(k, n, l) }
            }
            if (reg != sub) {
                val s2 = sub.translate(-reg.x, -reg.y)
                val d = result.data.copyOf()
                val w = result.w; val h = result.h
                for (y in 0 until h) for (x in 0 until w) {
                    if (y < s2.y || x < s2.x || x >= s2.right || y >= s2.bottom) { val o = (y * w + x) * 4; d[o] = 0; d[o + 1] = 0; d[o + 2] = 0; d[o + 3] = 0 }
                }
                result = Img(w, h, d, sub, result.linear)
            }
            results.add(p.result to result)
        }
        val last = results.lastOrNull() ?: throw FilterError()
        return last.second to outputRegion
    }

    private fun draw(dst: Img, src: Img, dx: Int, dy: Int, mode: BlendMode? = null, porter: PorterDuff.Mode? = null): Img {
        val db = dst.bitmap()
        val sb = src.bitmap()
        val p = Paint()
        if (mode != null) p.blendMode = mode
        if (porter != null) p.xfermode = PorterDuffXfermode(porter)
        Canvas(db).drawBitmap(sb, dx.toFloat(), dy.toFloat(), p)
        sb.recycle()
        return fromBitmap(db, dst.linear).let { Img(it.w, it.h, it.data, dst.region, dst.linear) }
    }

    private fun scaleCoords(x: Float, y: Float, ts: Ts): FloatArray { val s = ts.scaleXY(); return floatArrayOf(x * s[0], y * s[1]) }

    private fun resolveStdDev(sx: Float, sy: Float, ts: Ts): Triple<Double, Double, Boolean>? {
        val s = scaleCoords(sx, sy, ts)
        var dx = s[0]; var dy = s[1]
        if (abs(dx) <= 4 * Float.MIN_VALUE && abs(dy) <= 4 * Float.MIN_VALUE) return null
        if (dx < 0.05f) dx = 0f
        if (dy < 0.05f) dy = 0f
        return Triple(dx.toDouble(), dy.toDouble(), dx >= 2f || dy >= 2f)
    }

    private fun doBlur(d: ByteArray, w: Int, h: Int, sx: Double, sy: Double, box: Boolean) { if (box) boxBlur(sx, sy, d, w, h) else iirBlur(sx, sy, d, w, h) }

    private fun dropShadow(fe: FeKind.DropShadow, cs: Boolean, ts: Ts, input: Img): Img {
        val (dx, dy) = scaleCoords(fe.dx, fe.dy, ts).let { it[0] to it[1] }
        val inp = input.toSpace(cs)
        val shadow = inp.data.copyOf()
        resolveStdDev(fe.sdx, fe.sdy, ts)?.let { doBlur(shadow, inp.w, inp.h, it.first, it.second, it.third) }
        val a0 = Math.round(fe.opacity * 255f).coerceIn(0, 255) / 255f
        var i = 0
        while (i < shadow.size) {
            val pa = (shadow[i + 3].toInt() and 0xFF) / 255f
            val a = a0 * pa
            shadow[i] = Math.round(fe.color.r / 255f * a * 255f).toByte()
            shadow[i + 1] = Math.round(fe.color.g / 255f * a * 255f).toByte()
            shadow[i + 2] = Math.round(fe.color.b / 255f * a * 255f).toByte()
            shadow[i + 3] = Math.round(a * 255f).toByte()
            i += 4
        }
        if (cs) Px.toLinear(shadow) else Px.toSrgb(shadow)
        var out = empty(inp.w, inp.h, cs)
        out = draw(out, Img(inp.w, inp.h, shadow, inp.region, cs), dx.toInt(), dy.toInt())
        out = draw(out, inp, 0, 0)
        return Img(out.w, out.h, out.data, IRect(0, 0, out.w, out.h), cs)
    }

    private fun blur(fe: FeKind.GaussianBlur, cs: Boolean, ts: Ts, input: Img): Img {
        val sd = resolveStdDev(fe.sdx, fe.sdy, ts) ?: return input
        val img = input.toSpace(cs)
        val d = if (img === input) img.data.copyOf() else img.data
        doBlur(d, img.w, img.h, sd.first, sd.second, sd.third)
        return Img(img.w, img.h, d, IRect(0, 0, img.w, img.h), cs)
    }

    private fun offset(fe: FeKind.Offset, ts: Ts, input: Img): Img {
        val s = scaleCoords(fe.dx, fe.dy, ts)
        if (abs(s[0]) <= 4 * Float.MIN_VALUE && abs(s[1]) <= 4 * Float.MIN_VALUE) return input
        val out = draw(empty(input.w, input.h, input.linear), input, s[0].toInt(), s[1].toInt())
        return Img(out.w, out.h, out.data, IRect(0, 0, out.w, out.h), input.linear)
    }

    private fun blendMode(mode: Int): BlendMode = when (mode) {
        1 -> BlendMode.MULTIPLY; 2 -> BlendMode.SCREEN; 3 -> BlendMode.OVERLAY; 4 -> BlendMode.DARKEN; 5 -> BlendMode.LIGHTEN
        6 -> BlendMode.COLOR_DODGE; 7 -> BlendMode.COLOR_BURN; 8 -> BlendMode.HARD_LIGHT; 9 -> BlendMode.SOFT_LIGHT; 10 -> BlendMode.DIFFERENCE
        11 -> BlendMode.EXCLUSION; 12 -> BlendMode.HUE; 13 -> BlendMode.SATURATION; 14 -> BlendMode.COLOR; 15 -> BlendMode.LUMINOSITY; 16 -> BlendMode.PLUS
        else -> BlendMode.SRC_OVER
    }

    private fun blend(fe: FeKind.Blend, cs: Boolean, reg: IRect, in1: Img, in2: Img): Img {
        val a = in1.toSpace(cs); val b = in2.toSpace(cs)
        var out = draw(empty(reg.w, reg.h, cs), b, 0, 0)
        out = draw(out, a, 0, 0, blendMode(fe.mode))
        return Img(out.w, out.h, out.data, IRect(0, 0, out.w, out.h), cs)
    }

    private fun composite(fe: FeKind.Composite, cs: Boolean, reg: IRect, in1: Img, in2: Img): Img {
        val a = in1.toSpace(cs); val b = in2.toSpace(cs)
        if (fe.op == 6) {
            val out = ByteArray(reg.w * reg.h * 4)
            val k1 = fe.k[0]; val k2 = fe.k[1]; val k3 = fe.k[2]; val k4 = fe.k[3]
            fun calc(i1: Int, i2: Int, max: Float): Float { val x1 = i1 / 255f; val x2 = i2 / 255f; return (k1 * x1 * x2 + k2 * x1 + k3 * x2 + k4).let { if (it > max) max else if (it >= 0f) it else 0f } }
            val n = minOf(a.data.size, b.data.size, out.size) / 4
            for (i in 0 until n) {
                val o = i * 4
                val al = calc(a.data[o + 3].toInt() and 0xFF, b.data[o + 3].toInt() and 0xFF, 1f)
                if (abs(al) <= 4 * Float.MIN_VALUE) continue
                for (c in 0 until 3) out[o + c] = (calc(a.data[o + c].toInt() and 0xFF, b.data[o + c].toInt() and 0xFF, al) * 255f).toInt().toByte()
                out[o + 3] = (al * 255f).toInt().toByte()
            }
            return Img(reg.w, reg.h, out, IRect(0, 0, reg.w, reg.h), cs)
        }
        var out = draw(empty(reg.w, reg.h, cs), b, 0, 0)
        val pd = when (fe.op) { 1 -> PorterDuff.Mode.SRC_IN; 2 -> PorterDuff.Mode.SRC_OUT; 3 -> PorterDuff.Mode.SRC_ATOP; 4 -> PorterDuff.Mode.XOR; 5 -> PorterDuff.Mode.ADD; else -> PorterDuff.Mode.SRC_OVER }
        out = if (fe.op == 5) draw(out, a, 0, 0, BlendMode.PLUS) else draw(out, a, 0, 0, null, pd)
        return Img(out.w, out.h, out.data, IRect(0, 0, out.w, out.h), cs)
    }

    private fun merge(fe: FeKind.Merge, cs: Boolean, reg: IRect, input: (FeInput) -> Img): Img {
        var out = empty(reg.w, reg.h, cs)
        for (i in fe.inputs) out = draw(out, input(i).toSpace(cs), 0, 0)
        return Img(out.w, out.h, out.data, IRect(0, 0, out.w, out.h), cs)
    }

    private fun flood(fe: FeKind.Flood, reg: IRect): Img {
        val b = Bitmap.createBitmap(reg.w, reg.h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.argb(Math.round(fe.opacity * 255f).coerceIn(0, 255), fe.color.r, fe.color.g, fe.color.b))
        return fromBitmap(b, false)
    }

    private fun tile(input: Img, reg: IRect): Img {
        val sub = input.region.translate(-reg.x, -reg.y)
        val x0 = maxOf(sub.x, 0); val y0 = maxOf(sub.y, 0)
        val x1 = minOf(sub.right, input.w); val y1 = minOf(sub.bottom, input.h)
        if (x1 <= x0 || y1 <= y0) throw FilterError()
        val tw = x1 - x0; val th = y1 - y0
        val td = ByteArray(tw * th * 4)
        for (y in 0 until th) System.arraycopy(input.data, ((y0 + y) * input.w + x0) * 4, td, y * tw * 4, tw * 4)
        val tb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Px.put(tb, td)
        val out = Bitmap.createBitmap(reg.w, reg.h, Bitmap.Config.ARGB_8888)
        val p = Paint()
        val sh = BitmapShader(tb, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        sh.setLocalMatrix(Ts.translate(sub.x.toFloat(), sub.y.toFloat()).toMatrix())
        p.shader = sh
        Canvas(out).drawRect(0f, 0f, reg.w.toFloat(), reg.h.toFloat(), p)
        tb.recycle()
        return fromBitmap(out, false)
    }

    private fun image(fe: FeKind.Image, reg: IRect, sub: IRect, ts: Ts): Img {
        val b = Bitmap.createBitmap(reg.w, reg.h, Bitmap.Config.ARGB_8888)
        val s = ts.scaleXY()
        val t = Ts(s[0], 0f, 0f, s[1], sub.x.toFloat(), sub.y.toFloat())
        val r = IRect(0, 0, reg.w, reg.h)
        SvgRender.renderNodes(fe.root, SvgRender.Ctx(r, r), t, b)
        return fromBitmap(b, false)
    }

    private fun f32Bound(min: Float, v: Float, max: Float) = if (v > max) max else if (v >= min) v else min
    private fun u8f(v: Float): Byte = (f32Bound(0f, v, 1f) * 255f).toInt().toByte()

    private fun transfer(f: FeTransfer, c0: Int): Int {
        val c = c0 / 255f
        val v = when (f) {
            FeTransfer.Identity -> c
            is FeTransfer.Table -> {
                val n = f.values.size - 1
                val k = minOf(Math.floor((c * n).toDouble()).toInt(), n)
                if (k == n) f.values[k] else { val vk = f.values[k]; val vk1 = f.values[k + 1]; vk + (c - k.toFloat() / n) * n * (vk1 - vk) }
            }
            is FeTransfer.Discrete -> { val n = f.values.size; f.values[minOf(Math.floor((c * n).toDouble()).toInt(), n - 1)] }
            is FeTransfer.Linear -> f.slope * c + f.intercept
            is FeTransfer.Gamma -> f.amplitude * Math.pow(c.toDouble(), f.exponent.toDouble()).toFloat() + f.offset
        }
        return (f32Bound(0f, v, 1f) * 255f).toInt()
    }

    private fun isDummy(f: FeTransfer) = when (f) { FeTransfer.Identity -> true; is FeTransfer.Table -> f.values.isEmpty(); is FeTransfer.Discrete -> f.values.isEmpty(); else -> false }

    private fun componentTransfer(fe: FeKind.ComponentTransfer, cs: Boolean, input: Img): Img {
        val img = input.toSpace(cs)
        val d = img.data.copyOf()
        Px.demultiply(d)
        var i = 0
        while (i < d.size) {
            if (!isDummy(fe.r)) d[i] = transfer(fe.r, d[i].toInt() and 0xFF).toByte()
            if (!isDummy(fe.b)) d[i + 2] = transfer(fe.b, d[i + 2].toInt() and 0xFF).toByte()
            if (!isDummy(fe.g)) d[i + 1] = transfer(fe.g, d[i + 1].toInt() and 0xFF).toByte()
            if (!isDummy(fe.a)) d[i + 3] = transfer(fe.a, d[i + 3].toInt() and 0xFF).toByte()
            i += 4
        }
        Px.multiply(d)
        return Img(img.w, img.h, d, IRect(0, 0, img.w, img.h), cs)
    }

    private fun colorMatrix(fe: FeKind.ColorMatrix, cs: Boolean, input: Img): Img {
        val img = input.toSpace(cs)
        val d = img.data.copyOf()
        Px.demultiply(d)
        var i = 0
        val m: FloatArray? = when (fe.type) {
            1 -> { val v = maxOf(fe.values[0], 0f); floatArrayOf(0.213f + 0.787f * v, 0.715f - 0.715f * v, 0.072f - 0.072f * v, 0.213f - 0.213f * v, 0.715f + 0.285f * v, 0.072f - 0.072f * v, 0.213f - 0.213f * v, 0.715f - 0.715f * v, 0.072f + 0.928f * v) }
            2 -> {
                val ang = Math.toRadians(fe.values[0].toDouble()).toFloat()
                val a1 = Math.cos(ang.toDouble()).toFloat(); val a2 = Math.sin(ang.toDouble()).toFloat()
                floatArrayOf(0.213f + 0.787f * a1 - 0.213f * a2, 0.715f - 0.715f * a1 - 0.715f * a2, 0.072f - 0.072f * a1 + 0.928f * a2,
                    0.213f - 0.213f * a1 + 0.143f * a2, 0.715f + 0.285f * a1 + 0.140f * a2, 0.072f - 0.072f * a1 - 0.283f * a2,
                    0.213f - 0.213f * a1 - 0.787f * a2, 0.715f - 0.715f * a1 + 0.715f * a2, 0.072f + 0.928f * a1 + 0.072f * a2)
            }
            else -> null
        }
        while (i < d.size) {
            val r = (d[i].toInt() and 0xFF) / 255f; val g = (d[i + 1].toInt() and 0xFF) / 255f; val b = (d[i + 2].toInt() and 0xFF) / 255f; val a = (d[i + 3].toInt() and 0xFF) / 255f
            when (fe.type) {
                0 -> {
                    val v = fe.values
                    d[i] = u8f(r * v[0] + g * v[1] + b * v[2] + a * v[3] + v[4])
                    d[i + 1] = u8f(r * v[5] + g * v[6] + b * v[7] + a * v[8] + v[9])
                    d[i + 2] = u8f(r * v[10] + g * v[11] + b * v[12] + a * v[13] + v[14])
                    d[i + 3] = u8f(r * v[15] + g * v[16] + b * v[17] + a * v[18] + v[19])
                }
                1, 2 -> {
                    m!!
                    d[i] = u8f(r * m[0] + g * m[1] + b * m[2])
                    d[i + 1] = u8f(r * m[3] + g * m[4] + b * m[5])
                    d[i + 2] = u8f(r * m[6] + g * m[7] + b * m[8])
                }
                3 -> { d[i] = 0; d[i + 1] = 0; d[i + 2] = 0; d[i + 3] = u8f(r * 0.2125f + g * 0.7154f + b * 0.0721f) }
            }
            i += 4
        }
        Px.multiply(d)
        return Img(img.w, img.h, d, IRect(0, 0, img.w, img.h), cs)
    }

    private fun convolve(fe: FeKind.ConvolveMatrix, cs: Boolean, input: Img): Img {
        val img = input.toSpace(cs)
        val src = img.data.copyOf()
        if (fe.preserveAlpha) Px.demultiply(src)
        val w = img.w; val h = img.h
        val out = ByteArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var nr = 0f; var ng = 0f; var nb = 0f; var na = 0f
            for (oy in 0 until fe.rows) for (ox in 0 until fe.cols) {
                var tx = x - fe.tx + ox
                var ty = y - fe.ty + oy
                when (fe.edgeMode) {
                    0 -> if (tx < 0 || tx > w - 1 || ty < 0 || ty > h - 1) continue
                    1 -> { tx = tx.coerceIn(0, w - 1); ty = ty.coerceIn(0, h - 1) }
                    else -> { while (tx < 0) tx += w; tx %= w; while (ty < 0) ty += h; ty %= h }
                }
                val k = fe.matrix[(fe.rows - oy - 1) * fe.cols + (fe.cols - ox - 1)]
                val o = (ty * w + tx) * 4
                nr += (src[o].toInt() and 0xFF) / 255f * k
                ng += (src[o + 1].toInt() and 0xFF) / 255f * k
                nb += (src[o + 2].toInt() and 0xFF) / 255f * k
                if (!fe.preserveAlpha) na += (src[o + 3].toInt() and 0xFF) / 255f * k
            }
            val o = (y * w + x) * 4
            na = if (fe.preserveAlpha) (src[o + 3].toInt() and 0xFF) / 255f else na / fe.divisor + fe.bias
            val ba = f32Bound(0f, na, 1f)
            fun calc(v: Float): Byte {
                val x2 = v / fe.divisor + fe.bias * na
                val r = if (fe.preserveAlpha) f32Bound(0f, x2, 1f) * ba else f32Bound(0f, x2, ba)
                return (r * 255f + 0.5f).toInt().toByte()
            }
            out[o] = calc(nr); out[o + 1] = calc(ng); out[o + 2] = calc(nb); out[o + 3] = (ba * 255f + 0.5f).toInt().toByte()
        }
        return Img(w, h, out, IRect(0, 0, w, h), cs)
    }

    private fun morphology(fe: FeKind.Morphology, cs: Boolean, ts: Ts, input: Img): Img {
        val img = input.toSpace(cs)
        val src = img.data
        val s = scaleCoords(fe.rx, fe.ry, ts)
        if (!(s[0] > 0f && s[1] > 0f)) return Img(img.w, img.h, ByteArray(src.size), IRect(0, 0, img.w, img.h), cs)
        val w = img.w; val h = img.h
        val rx = minOf(Math.round(s[0]), w - 1); val ry = minOf(Math.round(s[1]), h - 1)
        val out = ByteArray(src.size)
        val erode = !fe.dilate
        for (y in 0 until h) for (x in 0 until w) {
            val nv = IntArray(4) { if (erode) 255 else 0 }
            for (ty in maxOf(0, y - ry)..minOf(y + ry, h - 1)) for (tx in maxOf(0, x - rx)..minOf(x + rx, w - 1)) {
                val o = (ty * w + tx) * 4
                for (c in 0 until 4) { val v = src[o + c].toInt() and 0xFF; nv[c] = if (erode) minOf(v, nv[c]) else maxOf(v, nv[c]) }
            }
            val o = (y * w + x) * 4
            for (c in 0 until 4) out[o + c] = nv[c].toByte()
        }
        return Img(w, h, out, IRect(0, 0, w, h), cs)
    }

    private fun displacement(fe: FeKind.DisplacementMap, reg: IRect, cs: Boolean, ts: Ts, in1: Img, in2: Img): Img {
        val a = in1.toSpace(cs)
        val m = in2.toSpace(cs).data.copyOf()
        Px.demultiply(m)
        val s = scaleCoords(fe.scale, fe.scale, ts)
        val w = a.w; val h = a.h
        val out = ByteArray(reg.w * reg.h * 4)
        var x = 0; var y = 0
        var i = 0
        while (i < m.size && i < out.size) {
            fun ch(c: Int) = (m[i + c].toInt() and 0xFF) / 255f - 0.5f
            val dx = ch(fe.xCh); val dy = ch(fe.yCh)
            val ox = Math.round(x + dx * s[0]); val oy = Math.round(y + dy * s[1])
            if (x < w && y < h && ox >= 0 && ox < w && oy >= 0 && oy < h) System.arraycopy(a.data, (oy * w + ox) * 4, out, (y * w + x) * 4, 4)
            x++
            if (x == w) { x = 0; y++ }
            i += 4
        }
        return Img(reg.w, reg.h, out, IRect(0, 0, reg.w, reg.h), cs)
    }

    private fun turbulence(fe: FeKind.Turbulence, reg: IRect, cs: Boolean, ts: Ts): Img {
        val out = ByteArray(reg.w * reg.h * 4)
        val s = ts.scaleXY()
        if (abs(s[0]) <= 4 * Float.MIN_VALUE || abs(s[1]) <= 4 * Float.MIN_VALUE) return Img(reg.w, reg.h, out, IRect(0, 0, reg.w, reg.h), cs)
        Turb.apply((reg.x - ts.tx).toDouble(), (reg.y - ts.ty).toDouble(), s[0].toDouble(), s[1].toDouble(), fe.bfx.toDouble(), fe.bfy.toDouble(), fe.octaves, fe.seed, fe.stitch, fe.fractal, out, reg.w, reg.h)
        Px.multiply(out)
        return Img(reg.w, reg.h, out, IRect(0, 0, reg.w, reg.h), cs)
    }

    private class V3(val x: Float, val y: Float, val z: Float) {
        fun dot(o: V3) = x * o.x + y * o.y + z * o.z
        fun len() = sqrt(x * x + y * y + z * z)
        fun norm(): V3? { val l = len(); return if (abs(l) <= 4 * Float.MIN_VALUE || l == 0f) null else V3(x / l, y / l, z / l) }
        operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
        operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    }

    private class Normal(val fx: Float, val fy: Float, val nx: Float, val ny: Float) {
        val zero get() = abs(nx) <= 4 * Float.MIN_VALUE && abs(ny) <= 4 * Float.MIN_VALUE
    }

    private fun diffuseFactor(fe: FeKind.DiffuseLighting, n: Normal, l: V3): Float {
        val k = if (n.zero) l.z else {
            val nx = n.nx * (fe.surfaceScale / 255f) * n.fx
            val ny = n.ny * (fe.surfaceScale / 255f) * n.fy
            val nn = V3(nx, ny, 1f)
            nn.dot(l) / nn.len()
        }
        return fe.constant * k
    }

    private fun specularFactor(fe: FeKind.SpecularLighting, n: Normal, l: V3): Float {
        val hv = l + V3(0f, 0f, 1f)
        val hl = hv.len()
        if (abs(hl) <= 4 * Float.MIN_VALUE || hl == 0f) return 0f
        val exp1 = abs(fe.exponent - 1f) <= 4 * Math.ulp(1f)
        val k = if (n.zero) {
            val ndh = hv.z / hl
            if (exp1) ndh else Math.pow(ndh.toDouble(), fe.exponent.toDouble()).toFloat()
        } else {
            val nx = n.nx * (fe.surfaceScale / 255f) * n.fx
            val ny = n.ny * (fe.surfaceScale / 255f) * n.fy
            val nn = V3(nx, ny, 1f)
            val ndh = nn.dot(hv) / nn.len() / hl
            if (exp1) ndh else Math.pow(ndh.toDouble(), fe.exponent.toDouble()).toFloat()
        }
        return fe.constant * k
    }

    private fun lighting(lightIn: FeLight, surfaceScale: Float, color: SvgColor, reg: IRect, cs: Boolean, ts: Ts, input: Img, diffuse: Boolean, factor: (Normal, V3) -> Float): Img {
        val w = reg.w; val h = reg.h
        val out = ByteArray(w * h * 4)
        val res = Img(w, h, out, IRect(0, 0, w, h), cs)
        val src = input.data
        val sw = input.w; val sh = input.h
        if (sw < 3 || sh < 3) return res
        val light = when (lightIn) {
            is FeLight.Distant -> lightIn
            is FeLight.Point -> {
                val p = ts.map(lightIn.x, lightIn.y)
                FeLight.Point(p[0] - reg.x, p[1] - reg.y, lightIn.z * sqrt(ts.sx * ts.sx + ts.sy * ts.sy) / sqrt(2f))
            }
            is FeLight.Spot -> {
                val sz = sqrt(ts.sx * ts.sx + ts.sy * ts.sy) / sqrt(2f)
                val p = ts.map(lightIn.x, lightIn.y)
                val q = ts.map(lightIn.px, lightIn.py)
                FeLight.Spot(p[0] - reg.x, p[1] - reg.y, lightIn.z * sz, q[0] - reg.x, q[1] - reg.y, lightIn.pz * sz, lightIn.exponent, lightIn.cone)
            }
        }
        fun a(x: Int, y: Int) = src[(y * sw + x) * 4 + 3].toInt() and 0xFF
        var lightVec = when (light) {
            is FeLight.Distant -> { val az = Math.toRadians(light.azimuth.toDouble()); val el = Math.toRadians(light.elevation.toDouble()); V3((Math.cos(az) * Math.cos(el)).toFloat(), (Math.sin(az) * Math.cos(el)).toFloat(), Math.sin(el).toFloat()) }
            else -> V3(1f, 1f, 1f)
        }
        fun lightColor(lv: V3): IntArray {
            if (light !is FeLight.Spot) return intArrayOf(color.r, color.g, color.b)
            val origin = V3(light.x, light.y, light.z)
            val dir0 = V3(light.px, light.py, light.pz) - origin
            val dir = dir0.norm() ?: dir0
            val mls = -lv.dot(dir)
            if (mls <= 0f) return intArrayOf(0, 0, 0)
            light.cone?.let { if (mls < Math.cos(Math.toRadians(it.toDouble())).toFloat()) return intArrayOf(0, 0, 0) }
            val f = Math.pow(mls.toDouble(), light.exponent.toDouble()).toFloat()
            fun c(v: Int) = (f32Bound(0f, v * f, 255f) + 0.5f).toInt()
            return intArrayOf(c(color.r), c(color.g), c(color.b))
        }
        fun calc(nx: Int, ny: Int, n: Normal) {
            if (light is FeLight.Point || light is FeLight.Spot) {
                val (lx, ly, lz) = if (light is FeLight.Point) Triple(light.x, light.y, light.z) else (light as FeLight.Spot).let { Triple(it.x, it.y, it.z) }
                val nz = a(nx, ny) / 255f * surfaceScale
                val v = V3(lx, ly, lz) - V3(nx.toFloat(), ny.toFloat(), nz)
                lightVec = v.norm() ?: v
            }
            val lc = lightColor(lightVec)
            val f = factor(n, lightVec)
            fun comp(x: Int) = (f32Bound(0f, x * f, 255f) + 0.5f).toInt()
            val r = comp(lc[0]); val g = comp(lc[1]); val b = comp(lc[2])
            val al = if (diffuse) 255 else maxOf(maxOf(r, g), b)
            val o = (ny * w + nx) * 4
            if (o + 3 < out.size) { out[o] = r.toByte(); out[o + 1] = g.toByte(); out[o + 2] = b.toByte(); out[o + 3] = al.toByte() }
        }
        val f23 = 2f / 3f; val f13 = 1f / 3f; val f12 = 0.5f; val f14 = 0.25f
        fun nrm(fx: Float, fy: Float, nx: Int, ny: Int) = Normal(fx, fy, -nx.toFloat(), -ny.toFloat())
        run {
            val c = a(0, 0); val r = a(1, 0); val b = a(0, 1); val br = a(1, 1)
            calc(0, 0, nrm(f23, f23, -2 * c + 2 * r - b + br, -2 * c - r + 2 * b + br))
        }
        run {
            val l = a(sw - 2, 0); val c = a(sw - 1, 0); val bl = a(sw - 2, 1); val b = a(sw - 1, 1)
            calc(sw - 1, 0, nrm(f23, f23, -2 * l + 2 * c - bl + b, -l - 2 * c + bl + 2 * b))
        }
        run {
            val t = a(0, sh - 2); val tr = a(1, sh - 2); val c = a(0, sh - 1); val r = a(1, sh - 1)
            calc(0, sh - 1, nrm(f23, f23, -t + tr - 2 * c + 2 * r, -2 * t - tr + 2 * c + r))
        }
        run {
            val tl = a(sw - 2, sh - 2); val t = a(sw - 1, sh - 2); val l = a(sw - 2, sh - 1); val c = a(sw - 1, sh - 1)
            calc(sw - 1, sh - 1, nrm(f23, f23, -tl + t - 2 * l + 2 * c, -tl - 2 * t + l + 2 * c))
        }
        for (x in 1 until sw - 1) {
            run {
                val l = a(x - 1, 0); val c = a(x, 0); val r = a(x + 1, 0); val bl = a(x - 1, 1); val b = a(x, 1); val br = a(x + 1, 1)
                calc(x, 0, nrm(f13, f12, -2 * l + 2 * r - bl + br, -l - 2 * c - r + bl + 2 * b + br))
            }
            run {
                val tl = a(x - 1, sh - 2); val t = a(x, sh - 2); val tr = a(x + 1, sh - 2); val l = a(x - 1, sh - 1); val c = a(x, sh - 1); val r = a(x + 1, sh - 1)
                calc(x, sh - 1, nrm(f13, f12, -tl + tr - 2 * l + 2 * r, -tl - 2 * t - tr + l + 2 * c + r))
            }
        }
        for (y in 1 until sh - 1) {
            run {
                val t = a(0, y - 1); val tr = a(1, y - 1); val c = a(0, y); val r = a(1, y); val b = a(0, y + 1); val br = a(1, y + 1)
                calc(0, y, nrm(f12, f13, -t + tr - 2 * c + 2 * r - b + br, -2 * t - tr + 2 * b + br))
            }
            run {
                val tl = a(sw - 2, y - 1); val t = a(sw - 1, y - 1); val l = a(sw - 2, y); val c = a(sw - 1, y); val bl = a(sw - 2, y + 1); val b = a(sw - 1, y + 1)
                calc(sw - 1, y, nrm(f12, f13, -tl + t - 2 * l + 2 * c - bl + b, -tl - 2 * t + bl + 2 * b))
            }
        }
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val tl = a(x - 1, y - 1); val t = a(x, y - 1); val tr = a(x + 1, y - 1); val l = a(x - 1, y); val r = a(x + 1, y)
            val bl = a(x - 1, y + 1); val b = a(x, y + 1); val br = a(x + 1, y + 1)
            calc(x, y, nrm(f14, f14, -tl + tr - 2 * l + 2 * r - bl + br, -tl - 2 * t - tr + bl + 2 * b + br))
        }
        return res
    }

    private fun createBoxGauss(sigma: Float): IntArray {
        if (sigma <= 0f) return IntArray(5) { 1 }
        val n = 5f
        val wIdeal = sqrt(12f * sigma * sigma / n) + 1f
        var wl = Math.floor(wIdeal.toDouble()).toInt()
        if (wl % 2 == 0) wl -= 1
        val wu = wl + 2
        val wlf = wl.toFloat()
        val mIdeal = (12f * sigma * sigma - n * wlf * wlf - 4f * n * wlf - 3f * n) / (-4f * wlf - 4f)
        val m = Math.round(mIdeal)
        return IntArray(5) { if (it < m) wl else wu }
    }

    private fun roundF(x: Float): Float { var v = x + 12582912.0f; v -= 12582912.0f; return v }

    private fun boxBlur(sx: Double, sy: Double, data: ByteArray, w: Int, h: Int) {
        val bh = createBoxGauss(sx.toFloat())
        val bv = createBoxGauss(sy.toFloat())
        val back = data.copyOf()
        for (i in 0 until 5) {
            val rh = (bh[i] - 1) / 2
            val rv = (bv[i] - 1) / 2
            boxVert(rv, data, back, w, h)
            boxHorz(rh, back, data, w, h)
        }
    }

    private fun boxVert(r: Int, back: ByteArray, front: ByteArray, w: Int, h: Int) {
        if (r == 0) { System.arraycopy(back, 0, front, 0, back.size); return }
        val iarr = 1f / (r + r + 1)
        for (i in 0 until w) {
            val colStart = i
            val colEnd = i + w * (h - 1)
            var ti = i; var li = ti; var ri = ti + r * w
            val v = IntArray(4)
            for (j in 0 until minOf(r, h)) { val o = (ti + j * w) * 4; for (c in 0 until 4) v[c] += back[o + c].toInt() and 0xFF }
            fun put() { val o = ti * 4; for (c in 0 until 4) front[o + c] = roundF(v[c] * iarr).toInt().toByte() }
            for (k in 0 until minOf(h, r + 1)) {
                if (ri <= colEnd) { val o = ri * 4; for (c in 0 until 4) v[c] += back[o + c].toInt() and 0xFF }
                ri += w
                put()
                ti += w
            }
            if (h <= r) continue
            for (k in (r + 1) until (h - r)) {
                val o1 = ri * 4; val o2 = li * 4
                for (c in 0 until 4) v[c] += (back[o1 + c].toInt() and 0xFF) - (back[o2 + c].toInt() and 0xFF)
                ri += w; li += w
                put()
                ti += w
            }
            for (k in 0 until minOf(h - r - 1, r)) {
                if (li >= colStart) { val o = li * 4; for (c in 0 until 4) v[c] -= back[o + c].toInt() and 0xFF }
                li += w
                put()
                ti += w
            }
        }
    }

    private fun boxHorz(r: Int, back: ByteArray, front: ByteArray, w: Int, h: Int) {
        if (r == 0) { System.arraycopy(back, 0, front, 0, back.size); return }
        val iarr = 1f / (r + r + 1)
        for (i in 0 until h) {
            val rowStart = i * w
            val rowEnd = (i + 1) * w - 1
            var ti = i * w; var li = ti; var ri = ti + r
            val v = IntArray(4)
            for (j in 0 until minOf(r, w)) { val o = (ti + j) * 4; for (c in 0 until 4) v[c] += back[o + c].toInt() and 0xFF }
            fun put() { val o = ti * 4; for (c in 0 until 4) front[o + c] = roundF(v[c] * iarr).toInt().toByte() }
            for (k in 0 until minOf(w, r + 1)) {
                if (ri <= rowEnd) { val o = ri * 4; for (c in 0 until 4) v[c] += back[o + c].toInt() and 0xFF }
                ri += 1
                put()
                ti += 1
            }
            if (w <= r) continue
            for (k in (r + 1) until (w - r)) {
                val o1 = ri * 4; val o2 = li * 4
                for (c in 0 until 4) v[c] += (back[o1 + c].toInt() and 0xFF) - (back[o2 + c].toInt() and 0xFF)
                ri += 1; li += 1
                put()
                ti += 1
            }
            for (k in 0 until minOf(w - r - 1, r)) {
                if (li >= rowStart) { val o = li * 4; for (c in 0 until 4) v[c] -= back[o + c].toInt() and 0xFF }
                li += 1
                put()
                ti += 1
            }
        }
    }

    private fun iirBlur(sx: Double, sy: Double, data: ByteArray, w: Int, h: Int) {
        val buf = DoubleArray(w * h)
        for (ch in 0 until 4) {
            for (i in 0 until w * h) buf[i] = (data[i * 4 + ch].toInt() and 0xFF) / 255.0
            gauss2d(buf, w, h, sx, sy, 4)
            for (i in 0 until w * h) data[i * 4 + ch] = (buf[i] * 255.0).toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun coeffs(sigma: Double, steps: Int): DoubleArray {
        val lambda = sigma * sigma / (2.0 * steps)
        val dnu = (1.0 + 2.0 * lambda - sqrt(1.0 + 4.0 * lambda)) / (2.0 * lambda)
        return doubleArrayOf(lambda, dnu)
    }

    private fun gauss2d(buf: DoubleArray, w: Int, h: Int, sx: Double, sy: Double, steps: Int) {
        var lx = 1.0; var dx = 1.0; var ly = 1.0; var dy = 1.0
        if (sx > 0.0) {
            val c = coeffs(sx, steps); lx = c[0]; dx = c[1]
            for (y in 0 until h) for (s in 0 until steps) {
                val idx = w * y
                for (x in 1 until w) buf[idx + x] += dx * buf[idx + x - 1]
                var x = w - 1
                while (x > 0) { buf[idx + x - 1] += dx * buf[idx + x]; x-- }
            }
        }
        if (sy > 0.0) {
            val c = coeffs(sy, steps); ly = c[0]; dy = c[1]
            for (x in 0 until w) for (s in 0 until steps) {
                var y = w
                while (y < buf.size) { buf[x + y] += dy * buf[x + y - w]; y += w }
                y = buf.size - w
                while (y > 0) { buf[x + y - w] += dy * buf[x + y]; y -= w }
            }
        }
        val post = Math.pow(sqrt(dx * dy) / sqrt(lx * ly), (2 * steps).toDouble())
        for (i in buf.indices) buf[i] *= post
    }

    private object Turb {
        const val RAND_M = 2147483647
        const val RAND_A = 16807
        const val RAND_Q = 127773
        const val RAND_R = 2836
        const val B_SIZE = 0x100
        const val B_LEN = B_SIZE + B_SIZE + 2
        const val BM = 0xff
        const val PERLIN_N = 0x1000

        class Stitch(var width: Int, var height: Int, var wrapX: Int, var wrapY: Int)

        fun random(seed: Int): Int { var r = RAND_A * (seed % RAND_Q) - RAND_R * (seed / RAND_Q); if (r <= 0) r += RAND_M; return r }

        fun apply(offX: Double, offY: Double, sx: Double, sy: Double, bfx: Double, bfy: Double, octaves: Int, seedIn: Int, stitch: Boolean, fractal: Boolean, out: ByteArray, w: Int, h: Int) {
            val lattice = IntArray(B_LEN)
            val grad = Array(4) { Array(B_LEN) { DoubleArray(2) } }
            var seed = seedIn
            if (seed <= 0) seed = -seed % (RAND_M - 1) + 1
            if (seed > RAND_M - 1) seed = RAND_M - 1
            for (k in 0 until 4) for (i in 0 until B_SIZE) {
                lattice[i] = i
                for (j in 0 until 2) { seed = random(seed); grad[k][i][j] = ((seed % (B_SIZE + B_SIZE)) - B_SIZE).toDouble() / B_SIZE }
                val s = sqrt(grad[k][i][0] * grad[k][i][0] + grad[k][i][1] * grad[k][i][1])
                grad[k][i][0] /= s; grad[k][i][1] /= s
            }
            for (i in B_SIZE - 1 downTo 1) {
                val k = lattice[i]
                seed = random(seed)
                val j = seed % B_SIZE
                lattice[i] = lattice[j]
                lattice[j] = k
            }
            for (i in 0 until B_SIZE + 2) {
                lattice[B_SIZE + i] = lattice[i]
                for (g in grad) for (j in 0 until 2) g[B_SIZE + i][j] = g[i][j]
            }
            for (y in 0 until h) for (x in 0 until w) {
                val o = (y * w + x) * 4
                for (ch in 0 until 4) {
                    val tx = (x + offX) / sx; val ty = (y + offY) / sy
                    var n = turbulence(ch, tx, ty, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), bfx, bfy, octaves, fractal, stitch, lattice, grad)
                    n = if (fractal) (n * 255.0 + 255.0) / 2.0 else n * 255.0
                    out[o + ch] = ((n.toFloat().let { if (it > 255f) 255f else if (it >= 0f) it else 0f }) + 0.5f).toInt().toByte()
                }
            }
        }

        fun turbulence(ch: Int, x0: Double, y0: Double, tileX: Double, tileY: Double, tw: Double, th: Double, bfx0: Double, bfy0: Double, octaves: Int, fractal: Boolean, doStitch: Boolean, lat: IntArray, grad: Array<Array<DoubleArray>>): Double {
            var bfx = bfx0; var bfy = bfy0
            val st = if (doStitch) {
                if (bfx != 0.0) { val lo = Math.floor(tw * bfx) / tw; val hi = Math.ceil(tw * bfx) / tw; bfx = if (bfx / lo < hi / bfx) lo else hi }
                if (bfy != 0.0) { val lo = Math.floor(th * bfy) / th; val hi = Math.ceil(th * bfy) / th; bfy = if (bfy / lo < hi / bfy) lo else hi }
                val ww = (tw * bfx + 0.5).toInt(); val hh = (th * bfy + 0.5).toInt()
                Stitch(ww, hh, (tileX * bfx + PERLIN_N + ww).toInt(), (tileY * bfy + PERLIN_N + hh).toInt())
            } else null
            var sum = 0.0
            var x = x0 * bfx; var y = y0 * bfy
            var ratio = 1.0
            for (o in 0 until octaves) {
                val n = noise2(ch, x, y, lat, grad, st)
                sum += if (fractal) n / ratio else abs(n) / ratio
                x *= 2.0; y *= 2.0; ratio *= 2.0
                if (st != null) { st.width *= 2; st.wrapX = 2 * st.wrapX - PERLIN_N; st.height *= 2; st.wrapY = 2 * st.wrapY - PERLIN_N }
            }
            return sum
        }

        fun sCurve(t: Double) = t * t * (3.0 - 2.0 * t)
        fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)

        fun noise2(ch: Int, x: Double, y: Double, lat: IntArray, grad: Array<Array<DoubleArray>>, st: Stitch?): Double {
            var t = x + PERLIN_N
            var bx0 = t.toInt(); var bx1 = bx0 + 1
            val rx0 = t - t.toLong().toDouble(); val rx1 = rx0 - 1.0
            t = y + PERLIN_N
            var by0 = t.toInt(); var by1 = by0 + 1
            val ry0 = t - t.toLong().toDouble(); val ry1 = ry0 - 1.0
            if (st != null) {
                if (bx0 >= st.wrapX) bx0 -= st.width
                if (bx1 >= st.wrapX) bx1 -= st.width
                if (by0 >= st.wrapY) by0 -= st.height
                if (by1 >= st.wrapY) by1 -= st.height
            }
            bx0 = bx0 and BM; bx1 = bx1 and BM; by0 = by0 and BM; by1 = by1 and BM
            val i = lat[bx0]; val j = lat[bx1]
            val b00 = lat[i + by0]; val b10 = lat[j + by0]; val b01 = lat[i + by1]; val b11 = lat[j + by1]
            val sx = sCurve(rx0); val sy = sCurve(ry0)
            var q = grad[ch][b00]; var u = rx0 * q[0] + ry0 * q[1]
            q = grad[ch][b10]; var v = rx1 * q[0] + ry0 * q[1]
            val a = lerp(sx, u, v)
            q = grad[ch][b01]; u = rx0 * q[0] + ry1 * q[1]
            q = grad[ch][b11]; v = rx1 * q[0] + ry1 * q[1]
            val b = lerp(sx, u, v)
            return lerp(sy, a, b)
        }
    }
}
