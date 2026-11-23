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
 * Copyright (c) Yevhenii Reizner, The resvg Authors, The Linebender Authors,
 * Copyright (c) 2011 Google Inc. (Skia / tiny-skia, BSD-3-Clause), and
 * Copyright (c) 2018 The Kurbo Authors / Raph Levien (Apache-2.0 OR MIT),
 * licensed under MPL-2.0 / Apache-2.0 / MIT (tiny-skia under BSD-3-Clause; fontdb under MIT).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package com.omnireader.core.engine.svg

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.sqrt

internal class Ts(val sx: Float, val ky: Float, val kx: Float, val sy: Float, val tx: Float, val ty: Float) {
    companion object {
        val IDENTITY = Ts(1f, 0f, 0f, 1f, 0f, 0f)
        fun translate(x: Float, y: Float) = Ts(1f, 0f, 0f, 1f, x, y)
        fun scale(x: Float, y: Float) = Ts(x, 0f, 0f, y, 0f, 0f)
        fun rotate(deg: Float): Ts { val r = Math.toRadians(deg.toDouble()); val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat(); return Ts(c, s, -s, c, 0f, 0f) }
        fun fromBbox(r: SRect) = Ts(r.w, 0f, 0f, r.h, r.x, r.y)
        fun of(d: DoubleArray) = Ts(d[0].toFloat(), d[1].toFloat(), d[2].toFloat(), d[3].toFloat(), d[4].toFloat(), d[5].toFloat())
    }

    val isIdentity get() = sx == 1f && ky == 0f && kx == 0f && sy == 1f && tx == 0f && ty == 0f
    val hasSkew get() = kx != 0f || ky != 0f
    val isFinite get() = sx.isFinite() && ky.isFinite() && kx.isFinite() && sy.isFinite() && tx.isFinite() && ty.isFinite()
    fun scaleXY(): FloatArray = floatArrayOf(sqrt(sx * sx + kx * kx), sqrt(ky * ky + sy * sy))
    val isValid: Boolean get() { if (!isFinite) return false; val s = scaleXY(); return !(abs(s[0]) <= 1.1920929E-7f || abs(s[1]) <= 1.1920929E-7f) }

    fun pre(o: Ts): Ts = concat(this, o)
    fun post(o: Ts): Ts = concat(o, this)
    fun preTranslate(x: Float, y: Float) = pre(translate(x, y))
    fun preScale(x: Float, y: Float) = pre(scale(x, y))
    fun postTranslate(x: Float, y: Float) = post(translate(x, y))

    fun map(x: Float, y: Float): FloatArray = floatArrayOf(x * sx + y * kx + tx, x * ky + y * sy + ty)

    fun invert(): Ts? {
        if (isIdentity) return this
        if (!hasSkew) {
            if (sx != 1f || sy != 1f) {
                val ix = 1f / sx; val iy = 1f / sy
                val r = Ts(ix, 0f, 0f, iy, -tx * ix, -ty * iy)
                return if (r.isFinite) r else null
            }
            return translate(-tx, -ty)
        }
        val det = sx.toDouble() * sy.toDouble() - kx.toDouble() * ky.toDouble()
        val tol = 1.0f / 4096f
        if (abs(det.toFloat()) <= tol * tol * tol) return null
        val inv = 1.0 / det
        val r = Ts(
            (sy * inv).toFloat(), (-ky * inv).toFloat(), (-kx * inv).toFloat(), (sx * inv).toFloat(),
            ((kx.toDouble() * ty - sy.toDouble() * tx) * inv).toFloat(), ((ky.toDouble() * tx - sx.toDouble() * ty) * inv).toFloat()
        )
        return if (r.isFinite) r else null
    }

    fun toMatrix(): Matrix = Matrix().also { it.setValues(floatArrayOf(sx, kx, tx, ky, sy, ty, 0f, 0f, 1f)) }

    private fun concat(a: Ts, b: Ts) = Ts(
        a.sx * b.sx + a.kx * b.ky, a.ky * b.sx + a.sy * b.ky,
        a.sx * b.kx + a.kx * b.sy, a.ky * b.kx + a.sy * b.sy,
        a.sx * b.tx + a.kx * b.ty + a.tx, a.ky * b.tx + a.sy * b.ty + a.ty
    )
}

internal class SRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
    val nonZero get() = w > 0f && h > 0f && x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()

    companion object {
        fun ltrb(l: Float, t: Float, r: Float, b: Float): SRect? = if (l <= r && t <= b && l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite()) SRect(l, t, r - l, b - t) else null
        fun nz(x: Float, y: Float, w: Float, h: Float): SRect? { val r = SRect(x, y, w, h); return if (r.nonZero && (x + w).isFinite() && (y + h).isFinite()) r else null }
        fun fromPoints(p: FloatArray): SRect? {
            if (p.isEmpty()) return null
            var l = p[0]; var t = p[1]; var r = p[0]; var b = p[1]
            var i = 2
            while (i < p.size) { l = minOf(l, p[i]); r = maxOf(r, p[i]); t = minOf(t, p[i + 1]); b = maxOf(b, p[i + 1]); i += 2 }
            return ltrb(l, t, r, b)
        }
    }

    fun transform(ts: Ts): SRect? {
        if (ts.isIdentity) return this
        val pts = if (ts.hasSkew) {
            val a = ts.map(x, y); val b = ts.map(right, y); val c = ts.map(x, bottom); val d = ts.map(right, bottom)
            floatArrayOf(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1])
        } else {
            val a = ts.map(x, y); val b = ts.map(right, bottom)
            floatArrayOf(a[0], a[1], b[0], b[1])
        }
        return fromPoints(pts)
    }

    fun transformNz(ts: Ts): SRect? = transform(ts)?.takeIf { it.nonZero }
    fun bboxTransform(b: SRect) = SRect(x * b.w + b.x, y * b.h + b.y, w * b.w, h * b.h)
    fun intersect(o: SRect): SRect? = ltrb(maxOf(x, o.x), maxOf(y, o.y), minOf(right, o.right), minOf(bottom, o.bottom))
    fun translateTo(nx: Float, ny: Float) = SRect(nx, ny, w, h)
    fun toIRect(): IRect = IRect(Math.floor(x.toDouble()).toInt(), Math.floor(y.toDouble()).toInt(), maxOf(1, Math.ceil(w.toDouble()).toInt()), maxOf(1, Math.ceil(h.toDouble()).toInt()))
    fun roundOut(): IRect? {
        val l = Math.floor(x.toDouble()).toInt(); val t = Math.floor(y.toDouble()).toInt()
        val r = Math.ceil(right.toDouble()).toInt(); val b = Math.ceil(bottom.toDouble()).toInt()
        return IRect(l, t, maxOf(1, r - l), maxOf(1, b - t))
    }
}

internal class IRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
    fun translate(dx: Int, dy: Int) = IRect(x + dx, y + dy, w, h)
    fun fitTo(b: IRect): IRect? {
        val l = maxOf(x, b.x); val t = maxOf(y, b.y); val r = minOf(right, b.right); val bt = minOf(bottom, b.bottom)
        return if (r > l && bt > t) IRect(l, t, r - l, bt - t) else null
    }
    fun toRect() = SRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
    override fun equals(other: Any?) = other is IRect && other.x == x && other.y == y && other.w == w && other.h == h
    override fun hashCode() = x * 31 + y * 17 + w * 7 + h
}

internal class SPath(val verbs: ByteArray, val pts: FloatArray) {
    companion object {
        const val MOVE: Byte = 0
        const val LINE: Byte = 1
        const val QUAD: Byte = 2
        const val CUBIC: Byte = 3
        const val CLOSE: Byte = 4

        fun rect(r: SRect): SPath = SPath(byteArrayOf(MOVE, LINE, LINE, LINE, CLOSE), floatArrayOf(r.x, r.y, r.right, r.y, r.right, r.bottom, r.x, r.bottom))

        private fun cubicExtrema(a: Float, b: Float, c: Float, d: Float, out: MutableList<Float>) {
            val aa = -a + 3 * b - 3 * c + d
            val bb = 2 * (a - 2 * b + c)
            val cc = b - a
            if (abs(aa) < 1e-12f) {
                if (abs(bb) > 1e-12f) { val t = -cc / bb; if (t > 0f && t < 1f) out.add(t) }
                return
            }
            val disc = bb * bb - 4 * aa * cc
            if (disc < 0f) return
            val sq = sqrt(disc)
            val t1 = (-bb + sq) / (2 * aa); val t2 = (-bb - sq) / (2 * aa)
            if (t1 > 0f && t1 < 1f) out.add(t1)
            if (t2 > 0f && t2 < 1f) out.add(t2)
        }
    }

    val isEmpty get() = verbs.isEmpty()

    fun bounds(): SRect? = SRect.fromPoints(pts)

    fun tightBounds(): SRect? {
        if (pts.size < 2) return null
        var minX = pts[0]; var minY = pts[1]; var maxX = pts[0]; var maxY = pts[1]
        fun add(x: Float, y: Float) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }
        var p = 0
        var lastX = 0f; var lastY = 0f
        var moveX = 0f; var moveY = 0f
        val ts = ArrayList<Float>(4)
        for (v in verbs) {
            when (v) {
                MOVE -> { add(pts[p], pts[p + 1]); lastX = pts[p]; lastY = pts[p + 1]; moveX = lastX; moveY = lastY; p += 2 }
                LINE -> { add(pts[p], pts[p + 1]); lastX = pts[p]; lastY = pts[p + 1]; p += 2 }
                QUAD -> {
                    val x1 = pts[p]; val y1 = pts[p + 1]; val x = pts[p + 2]; val y = pts[p + 3]
                    add(x, y)
                    for (axis in 0..1) {
                        val a = if (axis == 0) lastX else lastY; val b = if (axis == 0) x1 else y1; val c = if (axis == 0) x else y
                        val den = a - 2 * b + c
                        if (den != 0f) { val t = (a - b) / den; if (t > 0f && t < 1f) { val mt = 1 - t; add(mt * mt * lastX + 2 * mt * t * x1 + t * t * x, mt * mt * lastY + 2 * mt * t * y1 + t * t * y) } }
                    }
                    lastX = x; lastY = y; p += 4
                }
                CUBIC -> {
                    val x1 = pts[p]; val y1 = pts[p + 1]; val x2 = pts[p + 2]; val y2 = pts[p + 3]; val x = pts[p + 4]; val y = pts[p + 5]
                    add(x, y)
                    ts.clear()
                    cubicExtrema(lastX, x1, x2, x, ts)
                    cubicExtrema(lastY, y1, y2, y, ts)
                    for (t in ts) {
                        val mt = 1 - t
                        val a = mt * mt * mt; val b = 3 * mt * mt * t; val c = 3 * mt * t * t; val d = t * t * t
                        add(a * lastX + b * x1 + c * x2 + d * x, a * lastY + b * y1 + c * y2 + d * y)
                    }
                    lastX = x; lastY = y; p += 6
                }
                CLOSE -> { lastX = moveX; lastY = moveY }
            }
        }
        return SRect.ltrb(minX, minY, maxX, maxY)
    }

    fun transform(ts: Ts): SPath {
        if (ts.isIdentity) return this
        val o = FloatArray(pts.size)
        var i = 0
        while (i < pts.size) { val m = ts.map(pts[i], pts[i + 1]); o[i] = m[0]; o[i + 1] = m[1]; i += 2 }
        return SPath(verbs, o)
    }

    fun toAndroid(evenOdd: Boolean = false): Path {
        val path = Path()
        path.fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        var p = 0
        for (v in verbs) {
            when (v) {
                MOVE -> { path.moveTo(pts[p], pts[p + 1]); p += 2 }
                LINE -> { path.lineTo(pts[p], pts[p + 1]); p += 2 }
                QUAD -> { path.quadTo(pts[p], pts[p + 1], pts[p + 2], pts[p + 3]); p += 4 }
                CUBIC -> { path.cubicTo(pts[p], pts[p + 1], pts[p + 2], pts[p + 3], pts[p + 4], pts[p + 5]); p += 6 }
                CLOSE -> path.close()
            }
        }
        return path
    }

    inline fun forEachSegment(f: (Byte, FloatArray) -> Unit) {
        var p = 0
        val buf = FloatArray(6)
        for (v in verbs) {
            val n = when (v) { MOVE, LINE -> 2; QUAD -> 4; CUBIC -> 6; else -> 0 }
            for (k in 0 until n) buf[k] = pts[p + k]
            p += n
            f(v, buf)
        }
    }
}

internal class SPathBuilder {
    private val verbs = ArrayList<Byte>()
    private val pts = ArrayList<Float>()
    private var lastMoveIdx = 0
    private var moveRequired = true

    val size get() = verbs.size
    fun isEmpty() = verbs.isEmpty()
    fun lastPoint(): FloatArray? = if (pts.size >= 2) floatArrayOf(pts[pts.size - 2], pts[pts.size - 1]) else null

    fun moveTo(x: Float, y: Float) {
        if (verbs.lastOrNull() == SPath.MOVE) { pts[pts.size - 2] = x; pts[pts.size - 1] = y }
        else { lastMoveIdx = pts.size; moveRequired = false; verbs.add(SPath.MOVE); pts.add(x); pts.add(y) }
    }
    private fun inject() {
        if (moveRequired) {
            if (lastMoveIdx + 1 < pts.size) moveTo(pts[lastMoveIdx], pts[lastMoveIdx + 1]) else moveTo(0f, 0f)
        }
    }
    fun lineTo(x: Float, y: Float) { inject(); verbs.add(SPath.LINE); pts.add(x); pts.add(y) }
    fun quadTo(x1: Float, y1: Float, x: Float, y: Float) { inject(); verbs.add(SPath.QUAD); pts.add(x1); pts.add(y1); pts.add(x); pts.add(y) }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) { inject(); verbs.add(SPath.CUBIC); pts.add(x1); pts.add(y1); pts.add(x2); pts.add(y2); pts.add(x); pts.add(y) }
    fun close() { if (verbs.isNotEmpty() && verbs.last() != SPath.CLOSE) verbs.add(SPath.CLOSE); moveRequired = true }

    fun arcTo(rx: Float, ry: Float, rot: Float, large: Boolean, sweep: Boolean, x: Float, y: Float) {
        val prev = lastPoint() ?: return
        val segs = ArrayList<SvgPathSeg>()
        if (!SvgSyntax.arcToCubics(prev[0].toDouble(), prev[1].toDouble(), rx.toDouble(), ry.toDouble(), rot.toDouble(), large, sweep, x.toDouble(), y.toDouble(), segs)) segs.add(SvgPathSeg.LineTo(x.toDouble(), y.toDouble()))
        for (s in segs) when (s) {
            is SvgPathSeg.CubicTo -> cubicTo(s.x1.toFloat(), s.y1.toFloat(), s.x2.toFloat(), s.y2.toFloat(), s.x.toFloat(), s.y.toFloat())
            is SvgPathSeg.LineTo -> lineTo(s.x.toFloat(), s.y.toFloat())
            else -> {}
        }
    }

    fun finish(): SPath? {
        if (verbs.size <= 1) return null
        if (pts.isEmpty()) return null
        return SPath(verbs.toByteArray(), pts.toFloatArray())
    }

    companion object {
        fun fromSegments(segs: List<SvgPathSeg>): SPath? {
            val b = SPathBuilder()
            for (s in segs) when (s) {
                is SvgPathSeg.MoveTo -> b.moveTo(s.x.toFloat(), s.y.toFloat())
                is SvgPathSeg.LineTo -> b.lineTo(s.x.toFloat(), s.y.toFloat())
                is SvgPathSeg.QuadTo -> b.quadTo(s.x1.toFloat(), s.y1.toFloat(), s.x.toFloat(), s.y.toFloat())
                is SvgPathSeg.CubicTo -> b.cubicTo(s.x1.toFloat(), s.y1.toFloat(), s.x2.toFloat(), s.y2.toFloat(), s.x.toFloat(), s.y.toFloat())
                SvgPathSeg.Close -> b.close()
            }
            return b.finish()
        }
    }
}

internal class SvgStop(val offset: Float, val r: Int, val g: Int, val b: Int, val opacity: Float)

internal sealed class RPaint {
    class Solid(val r: Int, val g: Int, val b: Int) : RPaint() { override val units = UNITS_USER }
    class Linear(val id: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float, override val units: Int, val transform: Ts, val spread: Int, val linearRgb: Boolean, val stops: List<SvgStop>) : RPaint()
    class Radial(val id: String, val cx: Float, val cy: Float, val r: Float, val fx: Float, val fy: Float, val fr: Float, override val units: Int, val transform: Ts, val spread: Int, val linearRgb: Boolean, val stops: List<SvgStop>) : RPaint()
    class Pattern(val id: String, override val units: Int, override val contentUnits: Int, val transform: Ts, val rect: SRect, val viewBox: Pair<SRect, SvgAspect>?, val root: RGroup) : RPaint()

    abstract val units: Int
    open val contentUnits: Int get() = UNITS_USER

    companion object {
        const val UNITS_USER = 0
        const val UNITS_BBOX = 1
        const val SPREAD_PAD = 0
        const val SPREAD_REFLECT = 1
        const val SPREAD_REPEAT = 2
    }
}

internal class RFill(var paint: RPaint, val opacity: Float, val evenOdd: Boolean, var context: Int = 0) {
    fun copy() = RFill(paint, opacity, evenOdd, context)
}

internal class RStroke(
    var paint: RPaint, val dash: FloatArray?, val dashOffset: Float, val miter: Float, val opacity: Float, val width: Float,
    val cap: Int, val join: Int, var context: Int = 0
) {
    fun copy() = RStroke(paint, dash, dashOffset, miter, opacity, width, cap, join, context)

    fun applyTo(p: Paint) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = width
        p.strokeMiter = miter
        p.strokeCap = when (cap) { 1 -> Paint.Cap.ROUND; 2 -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
        p.strokeJoin = when (join) { 2 -> Paint.Join.ROUND; 3 -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
    }

    companion object {
        const val CONTEXT_NONE = 0
        const val CONTEXT_PATH = 1
        const val CONTEXT_USE = 2
    }
}

internal sealed class RNode {
    open val id: String get() = ""
}

internal class RGroup : RNode() {
    override var id: String = ""
    var transform: Ts = Ts.IDENTITY
    var absTransform: Ts = Ts.IDENTITY
    var opacity: Float = 1f
    var blendMode: Int = 0
    var isolate: Boolean = false
    var clipPath: RClip? = null
    var mask: RMask? = null
    var filters: List<RFilter> = emptyList()
    var isContextElement = false
    var textBbox: SRect? = null
    var presetBbox: SRect? = null
    val children = ArrayList<RNode>()

    private var cachedBbox: Any? = UNSET
    private var cachedStroke: Any? = UNSET
    private var cachedLayer: Any? = UNSET

    fun hasChildren() = children.isNotEmpty()

    fun shouldIsolate() = isolate || opacity != 1f || clipPath != null || mask != null || filters.isNotEmpty() || blendMode != 0

    fun boundingBox(): SRect? {
        if (cachedBbox === UNSET) cachedBbox = presetBbox ?: union { c -> c.bbox()?.let { toParent(c, it) } }
        return cachedBbox as SRect?
    }

    fun strokeBoundingBox(): SRect? {
        if (cachedStroke === UNSET) cachedStroke = presetBbox ?: union { c -> c.strokeBbox()?.let { toParent(c, it) } }
        return cachedStroke as SRect?
    }

    fun filtersBoundingBox(): SRect? {
        if (filters.isEmpty()) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (f in filters) { l = minOf(l, f.rect.x); t = minOf(t, f.rect.y); r = maxOf(r, f.rect.right); b = maxOf(b, f.rect.bottom) }
        return SRect.ltrb(l, t, r, b)?.takeIf { it.nonZero }
    }

    fun layerBoundingBox(): SRect? {
        if (cachedLayer === UNSET) {
            cachedLayer = filtersBoundingBox() ?: run {
                var bb = union { c -> if (c is RGroup) c.layerBoundingBox()?.transform(c.transform) else c.strokeBbox() } ?: return@run null
                val cp = clipPath
                if (cp != null) {
                    val clipBox = cp.root.strokeBoundingBox()?.transform(cp.transform) ?: return@run null
                    bb = bb.intersect(clipBox) ?: return@run null
                }
                bb.takeIf { it.nonZero }
            }
        }
        return cachedLayer as SRect?
    }

    fun absBoundingBox(): SRect? = union { it.absBbox() }

    private fun toParent(c: RNode, r: SRect): SRect = if (c is RGroup) (r.transform(c.transform) ?: r) else r

    private inline fun union(f: (RNode) -> SRect?): SRect? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var any = false
        for (c in children) {
            val x = f(c) ?: continue
            any = true
            l = minOf(l, x.x); t = minOf(t, x.y); r = maxOf(r, x.right); b = maxOf(b, x.bottom)
        }
        return if (any) SRect.ltrb(l, t, r, b) else null
    }

    fun invalidate() { cachedBbox = UNSET; cachedStroke = UNSET; cachedLayer = UNSET }

    companion object {
        private val UNSET = Any()
    }
}

internal class RPathNode(
    override val id: String, val visible: Boolean, var fill: RFill?, var stroke: RStroke?, val strokeFirst: Boolean, val antiAlias: Boolean,
    val data: SPath, val absTransform: Ts
) : RNode() {
    private var tight: Any? = UNSET
    private var strokeBox: Any? = UNSET

    fun copyWith(fill: RFill?, stroke: RStroke?) = RPathNode("", visible, fill, stroke, strokeFirst, antiAlias, data, absTransform)

    fun boundingBox(): SRect? { if (tight === UNSET) tight = data.tightBounds(); return tight as SRect? }

    fun strokeBoundingBox(): SRect? {
        if (strokeBox === UNSET) strokeBox = calcStrokeBbox(data) ?: boundingBox()
        return strokeBox as SRect?
    }

    fun absBoundingBox(): SRect? = if (absTransform.hasSkew) data.transform(absTransform).tightBounds() else boundingBox()?.transform(absTransform)

    private fun calcStrokeBbox(d: SPath): SRect? {
        val s = stroke ?: return null
        val p = Paint()
        p.isAntiAlias = true
        s.applyTo(p)
        val out = Path()
        @Suppress("DEPRECATION")
        p.getFillPath(d.toAndroid(), out)
        val pts = out.approximate(0.05f)
        if (pts.isEmpty()) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < pts.size) { l = minOf(l, pts[i + 1]); r = maxOf(r, pts[i + 1]); t = minOf(t, pts[i + 2]); b = maxOf(b, pts[i + 2]); i += 3 }
        return SRect.ltrb(l, t, r, b)
    }

    companion object {
        private val UNSET = Any()
    }
}

internal class RImageNode(val visible: Boolean, val width: Float, val height: Float, val smooth: Boolean, val bitmap: android.graphics.Bitmap?, val svg: SvgTree?, val absTransform: Ts) : RNode() {
    fun boundingBox() = SRect(0f, 0f, width, height)
}

internal class RGlyph(
    val ts: Ts, val text: CharArray, val start: Int, val end: Int, val ctxStart: Int, val ctxEnd: Int, val rtl: Boolean, val paint: Paint, val color: Boolean,
    val id: Int, val x: Float, val y: Float, val font: android.graphics.fonts.Font?, val scale: Float
)

internal class RGlyphRun(
    val visible: Boolean, var fill: RFill?, var stroke: RStroke?, val strokeFirst: Boolean, val antiAlias: Boolean,
    val glyphs: List<RGlyph>, val inkBox: SRect, val absTransform: Ts
) : RNode() {
    fun boundingBox(): SRect = inkBox

    fun strokeBoundingBox(): SRect {
        val s = stroke ?: return inkBox
        val e = s.width / 2f * (if (s.join == 0) maxOf(1f, s.miter) else 1f) * (if (s.cap == 2) 1.5f else 1f)
        return SRect(inkBox.x - e, inkBox.y - e, inkBox.w + 2 * e, inkBox.h + 2 * e)
    }
}

internal class RTextNode(override val id: String, val bbox: SRect, val flattened: RGroup, val absTransform: Ts) : RNode()

internal fun RNode.bbox(): SRect? = when (this) { is RGroup -> boundingBox(); is RPathNode -> boundingBox(); is RImageNode -> boundingBox(); is RGlyphRun -> boundingBox(); is RTextNode -> bbox }
internal fun RNode.strokeBbox(): SRect? = when (this) { is RGroup -> strokeBoundingBox(); is RPathNode -> strokeBoundingBox(); is RImageNode -> boundingBox(); is RGlyphRun -> strokeBoundingBox(); is RTextNode -> flattened.strokeBoundingBox() }
internal fun RNode.absBbox(): SRect? = when (this) { is RGroup -> absBoundingBox(); is RPathNode -> absBoundingBox(); is RImageNode -> boundingBox().transform(absTransform); is RGlyphRun -> boundingBox().transform(absTransform); is RTextNode -> bbox.transform(absTransform) }

internal class RClip(val id: String, var transform: Ts, val clipPath: RClip?, val root: RGroup)

internal class RMask(val id: String, val rect: SRect, val alpha: Boolean, val linearRgb: Boolean, val mask: RMask?, val root: RGroup)

internal class RFilter(val id: String, val rect: SRect, val primitives: List<RPrimitive>)

internal class RPrimitive(val rect: SRect, val linearRgb: Boolean, val result: String, val kind: FeKind)

internal sealed class FeInput {
    object SourceGraphic : FeInput()
    object SourceAlpha : FeInput()
    class Ref(val name: String) : FeInput()
}

internal sealed class FeTransfer {
    object Identity : FeTransfer()
    class Table(val values: FloatArray) : FeTransfer()
    class Discrete(val values: FloatArray) : FeTransfer()
    class Linear(val slope: Float, val intercept: Float) : FeTransfer()
    class Gamma(val amplitude: Float, val exponent: Float, val offset: Float) : FeTransfer()
}

internal sealed class FeLight {
    class Distant(val azimuth: Float, val elevation: Float) : FeLight()
    class Point(var x: Float, var y: Float, var z: Float) : FeLight()
    class Spot(var x: Float, var y: Float, var z: Float, var px: Float, var py: Float, var pz: Float, val exponent: Float, val cone: Float?) : FeLight()
}

internal sealed class FeKind {
    class Blend(val mode: Int, val in1: FeInput, val in2: FeInput) : FeKind()
    class ColorMatrix(val input: FeInput, val type: Int, val values: FloatArray) : FeKind()
    class ComponentTransfer(val input: FeInput, val r: FeTransfer, val g: FeTransfer, val b: FeTransfer, val a: FeTransfer) : FeKind()
    class Composite(val op: Int, val k: FloatArray, val in1: FeInput, val in2: FeInput) : FeKind()
    class ConvolveMatrix(val input: FeInput, val cols: Int, val rows: Int, val tx: Int, val ty: Int, val matrix: FloatArray, val divisor: Float, val bias: Float, val edgeMode: Int, val preserveAlpha: Boolean) : FeKind()
    class DiffuseLighting(val input: FeInput, val surfaceScale: Float, val constant: Float, val color: SvgColor, val light: FeLight) : FeKind()
    class SpecularLighting(val input: FeInput, val surfaceScale: Float, val constant: Float, val exponent: Float, val color: SvgColor, val light: FeLight) : FeKind()
    class DisplacementMap(val in1: FeInput, val in2: FeInput, val scale: Float, val xCh: Int, val yCh: Int) : FeKind()
    class DropShadow(val input: FeInput, val dx: Float, val dy: Float, val sdx: Float, val sdy: Float, val color: SvgColor, val opacity: Float) : FeKind()
    class Flood(val color: SvgColor, val opacity: Float) : FeKind()
    class GaussianBlur(val input: FeInput, val sdx: Float, val sdy: Float) : FeKind()
    class Image(val root: RGroup) : FeKind()
    class Merge(val inputs: List<FeInput>) : FeKind()
    class Morphology(val input: FeInput, val dilate: Boolean, val rx: Float, val ry: Float) : FeKind()
    class Offset(val input: FeInput, val dx: Float, val dy: Float) : FeKind()
    class Tile(val input: FeInput) : FeKind()
    class Turbulence(val bfx: Float, val bfy: Float, val octaves: Int, val seed: Int, val stitch: Boolean, val fractal: Boolean) : FeKind()
}

internal class SvgTree(val width: Float, val height: Float, val root: RGroup)
