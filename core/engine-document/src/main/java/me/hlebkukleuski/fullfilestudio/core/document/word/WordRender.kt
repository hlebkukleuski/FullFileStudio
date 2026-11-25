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

package me.hlebkukleuski.fullfilestudio.core.document.word

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

internal object WordRender {
    private fun roman(n0: Int, upper: Boolean): String {
        if (n0 <= 0 || n0 >= 4000) return n0.toString()
        var n = n0
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        val sb = StringBuilder()
        for (i in vals.indices) while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i] }
        return if (upper) sb.toString().uppercase() else sb.toString()
    }

    fun formatPage(n: Int, fmt: Int): String = when (fmt) {
        1 -> roman(n, true)
        2 -> roman(n, false)
        3 -> if (n > 0) ('A' + (n - 1) % 26).toString().repeat((n - 1) / 26 + 1) else n.toString()
        4 -> if (n > 0) ('a' + (n - 1) % 26).toString().repeat((n - 1) / 26 + 1) else n.toString()
        else -> n.toString()
    }

    fun drawPage(canvas: Canvas, page: LPage, totalPages: Int) {
        val fill = Paint()
        fill.isAntiAlias = true
        for (it in page.back) draw(canvas, it, page, totalPages, fill)
        for (it in page.items) draw(canvas, it, page, totalPages, fill)
    }

    private fun draw(canvas: Canvas, it: LItem, page: LPage, total: Int, fill: Paint) {
        when (it) {
            is LItem.Rect -> { fill.style = Paint.Style.FILL; fill.color = it.color; canvas.drawRect(it.x, it.y, it.x + it.w, it.y + it.h, fill) }
            is LItem.Image -> {
                if (it.image.kind == 2) {
                    fill.style = Paint.Style.FILL
                    if (it.image.hrNoShade) {
                        fill.color = it.image.hrColor
                        canvas.drawRect(it.x, it.y, it.x + it.w, it.y + it.h, fill)
                    } else {
                        fill.color = it.image.hrColor
                        canvas.drawRect(it.x, it.y, it.x + it.w, it.y + it.h, fill)
                        fill.color = 0xFFE3E3E3.toInt()
                        canvas.drawRect(it.x, it.y + it.h * 0.5f, it.x + it.w, it.y + it.h, fill)
                    }
                    return
                }
                val bmp = it.image.bitmap()
                if (bmp == null) {
                    if (it.image.kind == 1) {
                        fill.style = Paint.Style.STROKE
                        fill.strokeWidth = 0.75f
                        fill.color = 0xFF9A9A9A.toInt()
                        canvas.drawRect(it.x + 0.4f, it.y + 0.4f, it.x + it.w - 0.4f, it.y + it.h - 0.4f, fill)
                    }
                    return
                }
                if (bmp.isRecycled) return
                val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                if (it.clipW > 0f && it.clipW < it.w) {
                    canvas.save()
                    canvas.clipRect(it.x, it.y, it.x + it.clipW, it.y + it.h)
                    canvas.drawBitmap(bmp, null, RectF(it.x, it.y, it.x + it.w, it.y + it.h), p)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(bmp, null, RectF(it.x, it.y, it.x + it.w, it.y + it.h), p)
                }
            }
            is LItem.Comment -> {
                val r = RectF(it.x, it.y, it.x + it.w, it.y + it.h)
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = 0.6f
                fill.color = 0xFFE5A93C.toInt()
                fill.pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
                canvas.drawLine(it.anchorX, it.anchorY, it.x, it.y + 6f, fill)
                fill.pathEffect = null
                fill.style = Paint.Style.FILL
                fill.color = 0xFFFFF8DC.toInt()
                canvas.drawRoundRect(r, 3f, 3f, fill)
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = 0.6f
                fill.color = 0xFFE5A93C.toInt()
                canvas.drawRoundRect(r, 3f, 3f, fill)
                val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG)
                tp.color = 0xFF5A4200.toInt()
                tp.textSize = 6.5f
                canvas.save()
                canvas.clipRect(r.left + 2f, r.top + 1.5f, r.right - 2f, r.bottom - 1.5f)
                var cy = r.top + 7.5f
                for (ln in it.lines) {
                    canvas.drawText(ln, r.left + 3f, cy, tp)
                    cy += 7.5f
                }
                canvas.restore()
            }
            is LItem.Line -> line(canvas, it.x1, it.y1, it.x2, it.y2, it.border, fill)
            is LItem.Text -> text(canvas, it, fill)
            is LItem.PageField -> {
                val n = when (it.kind) { WInline.FIELD_PAGE -> page.pageNumber; WInline.FIELD_SECTIONPAGES -> page.sectionPageCount; else -> total }
                val s = formatPage(n, if (it.kind == WInline.FIELD_PAGE && it.format == 0) page.pageFormat.let { f -> if (f in 1..4) f else 0 } else it.format)
                canvas.drawText(s, it.x, it.y, it.style.paint)
            }
        }
    }

    private fun text(canvas: Canvas, t: LItem.Text, fill: Paint) {
        val st = t.style
        val p = st.paint
        val fm = p.fontMetrics
        if (st.highlight != 0 || st.shading != null) {
            fill.style = Paint.Style.FILL
            fill.color = if (st.highlight != 0) st.highlight else st.shading!!
            canvas.drawRect(t.x, t.y + fm.ascent, t.x + t.width, t.y + fm.descent, fill)
        }
        if (t.wordSpacing != 0f) p.wordSpacing = t.wordSpacing
        if (t.rtl) canvas.drawTextRun(t.text, 0, t.text.length, 0, t.text.length, t.x, t.y, true, p) else canvas.drawText(t.text, t.x, t.y, p)
        if (t.wordSpacing != 0f) p.wordSpacing = 0f
        val size = p.textSize
        val lineColor = if (st.underlineColor != WShading.AUTO) st.underlineColor else st.color
        if (st.underline != 0 && t.text.isNotBlank()) {
            fill.color = lineColor
            fill.style = Paint.Style.STROKE
            val th = maxOf(0.5f, size * (if (st.underline == 6) 0.1f else 0.05f))
            fill.strokeWidth = th
            fill.pathEffect = when (st.underline) { 4, 20 -> DashPathEffect(floatArrayOf(th, th * 2), 0f); 7, 23, 39, 55 -> DashPathEffect(floatArrayOf(th * 4, th * 2), 0f); 9, 25 -> DashPathEffect(floatArrayOf(th * 4, th * 2, th, th * 2), 0f); 10, 26 -> DashPathEffect(floatArrayOf(th * 4, th * 2, th, th * 2, th, th * 2), 0f); else -> null }
            val uy = t.y + size * 0.12f
            if (st.underline == 11 || st.underline == 27 || st.underline == 43) {
                val path = Path()
                var x = t.x
                path.moveTo(x, uy)
                val step = size * 0.15f
                var up = true
                while (x < t.x + t.width) { path.quadTo(x + step / 2, uy + (if (up) -step / 2 else step / 2), x + step, uy); x += step; up = !up }
                canvas.drawPath(path, fill)
            } else if (st.underline == 2) {
                var x = t.x
                for (word in t.text.split(' ')) {
                    val w = p.measureText(word)
                    if (word.isNotEmpty()) canvas.drawLine(x, uy, x + w, uy, fill)
                    x += w + p.measureText(" ") + t.wordSpacing
                }
            } else {
                canvas.drawLine(t.x, uy, t.x + t.width, uy, fill)
                if (st.underline == 3) canvas.drawLine(t.x, uy + th * 2, t.x + t.width, uy + th * 2, fill)
            }
            fill.pathEffect = null
        }
        if (st.strike || st.dstrike) {
            fill.color = st.color
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = maxOf(0.5f, size * 0.05f)
            val sy = t.y - size * 0.3f
            if (st.dstrike) {
                canvas.drawLine(t.x, sy - size * 0.05f, t.x + t.width, sy - size * 0.05f, fill)
                canvas.drawLine(t.x, sy + size * 0.05f, t.x + t.width, sy + size * 0.05f, fill)
            } else canvas.drawLine(t.x, sy, t.x + t.width, sy, fill)
        }
        st.border?.takeIf { it.visible }?.let { b ->
            val r = RectF(t.x - 1f, t.y + fm.ascent, t.x + t.width + 1f, t.y + fm.descent)
            line(canvas, r.left, r.top, r.right, r.top, b, fill); line(canvas, r.left, r.bottom, r.right, r.bottom, b, fill)
            line(canvas, r.left, r.top, r.left, r.bottom, b, fill); line(canvas, r.right, r.top, r.right, r.bottom, b, fill)
        }
    }

    fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, b: WBorder, fill: Paint) {
        if (!b.visible) return
        fill.style = Paint.Style.STROKE
        fill.color = if (b.color == WShading.AUTO) 0xFF000000.toInt() else b.color
        val w = maxOf(0.25f, if (b.type == 3) b.width8 / 8f else b.widthPt)
        fill.strokeWidth = w
        fill.pathEffect = when (b.type) {
            6 -> DashPathEffect(floatArrayOf(w, w), 0f)
            7, 22 -> DashPathEffect(floatArrayOf(w * 4, w * 3), 0f)
            8 -> DashPathEffect(floatArrayOf(w * 4, w * 2, w, w * 2), 0f)
            9 -> DashPathEffect(floatArrayOf(w * 4, w * 2, w, w * 2, w, w * 2), 0f)
            else -> null
        }
        if (b.type == 3 || b.type == 10) {
            val horiz = Math.abs(y2 - y1) < Math.abs(x2 - x1)
            val gap = w * 2
            val n = if (b.type == 10) 3 else 2
            for (k in 0 until n) {
                val o = (k - (n - 1) / 2f) * gap
                if (horiz) canvas.drawLine(x1, y1 + o, x2, y2 + o, fill) else canvas.drawLine(x1 + o, y1, x2 + o, y2, fill)
            }
        } else canvas.drawLine(x1, y1, x2, y2, fill)
        fill.pathEffect = null
    }
}
