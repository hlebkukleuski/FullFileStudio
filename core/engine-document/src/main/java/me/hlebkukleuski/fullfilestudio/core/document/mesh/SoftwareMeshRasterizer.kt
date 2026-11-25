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

package me.hlebkukleuski.fullfilestudio.core.document.mesh

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object MeshCamera {
    fun compute(
        width: Int, height: Int, yawDeg: Float, pitchDeg: Float, scale: Float, isYUp: Boolean,
        minX: Float, maxX: Float, minY: Float, maxY: Float, minZ: Float, maxZ: Float,
        mv: FloatArray, mvp: FloatArray
    ) {
        val projection = FloatArray(16)
        val view = FloatArray(16)
        val model = FloatArray(16)
        val rangeX = (maxX - minX).let { if (it.isFinite() && it > 0f) it else 1f }
        val rangeY = (maxY - minY).let { if (it.isFinite() && it > 0f) it else 1f }
        val rangeZ = (maxZ - minZ).let { if (it.isFinite() && it > 0f) it else 1f }
        val maxDim = maxOf(rangeX, rangeY, rangeZ).coerceAtLeast(1e-3f)
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        val radius = (0.5f * sqrt(rangeX * rangeX + rangeY * rangeY + rangeZ * rangeZ)).coerceAtLeast(maxDim * 0.5f)
        val halfFovY = Math.toRadians(22.5).toFloat()
        val halfFov = minOf(halfFovY, kotlin.math.atan(kotlin.math.tan(halfFovY) * aspect))
        val fitDist = maxOf(radius / kotlin.math.sin(halfFov) * 1.05f, maxDim * 2.25f)
        val eyeDist = fitDist / scale.coerceAtLeast(0.05f)
        val nearPlane = (maxDim * 0.01f).coerceAtLeast(0.001f)
        val farPlane = (eyeDist + maxDim * 12f).coerceAtLeast(100f)
        Matrix.perspectiveM(projection, 0, 45f, aspect, nearPlane, farPlane)
        Matrix.scaleM(projection, 0, 1f, -1f, 1f)
        val cx = if (minX.isFinite()) minX + rangeX / 2f else 0f
        val cy = if (minY.isFinite()) minY + rangeY / 2f else 0f
        val cz = if (minZ.isFinite()) minZ + rangeZ / 2f else 0f
        Matrix.setLookAtM(view, 0, 0f, 0f, eyeDist, 0f, 0f, 0f, 0f, 1f, 0f)
        val isFlat2dPlan = rangeZ < maxOf(rangeX, rangeY) * 0.005f
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, -pitchDeg, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yawDeg, 0f, 1f, 0f)
        if (!isYUp && !isFlat2dPlan) Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f)
        Matrix.translateM(model, 0, -cx, -cy, -cz)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
    }
}

class SoftwareMeshRasterizer {

    private class Tex(val w: Int, val h: Int, val px: IntArray, val translucent: Boolean)

    private val texCache = java.util.WeakHashMap<Bitmap, Tex>()

    private fun tex(b: Bitmap?): Tex? {
        if (b == null || b.isRecycled) return null
        texCache[b]?.let { return it }
        var src = b
        var step = 1
        while (max(src.width, src.height) / step > 1024) step *= 2
        if (step > 1) src = Bitmap.createScaledBitmap(b, max(1, b.width / step), max(1, b.height / step), true)
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        if (src !== b) src.recycle()
        var partial = 0
        val stride = max(1, px.size / 65536)
        var sampled = 0
        var i = 0
        while (i < px.size) {
            val a = px[i] ushr 24
            if (a in 16..239) partial++
            sampled++
            i += stride
        }
        val sw = px.size / max(1, if (step > 1) max(1, b.height / step) else b.height)
        val t = Tex(max(1, sw), max(1, px.size / max(1, sw)), px, b.hasAlpha() && partial * 50 > sampled)
        texCache[b] = t
        return t
    }

    private fun sample(t: Tex, u: Float, v: Float, out: FloatArray) {
        val fx = (u - floor(u)) * t.w - 0.5f
        val fy = (v - floor(v)) * t.h - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val ax = fx - x0
        val ay = fy - y0
        out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 0f
        for (dy in 0..1) for (dx in 0..1) {
            val wgt = (if (dx == 0) 1f - ax else ax) * (if (dy == 0) 1f - ay else ay)
            if (wgt == 0f) continue
            val xx = Math.floorMod(x0 + dx, t.w)
            val yy = Math.floorMod(y0 + dy, t.h)
            val c = t.px[yy * t.w + xx]
            out[0] += wgt * ((c shr 16) and 0xFF) / 255f
            out[1] += wgt * ((c shr 8) and 0xFF) / 255f
            out[2] += wgt * (c and 0xFF) / 255f
            out[3] += wgt * (c ushr 24) / 255f
        }
    }

    fun render(scene: MeshScene, bitmap: Bitmap, yawDeg: Float, pitchDeg: Float, zoom: Float, isLight: Boolean, maxTriangles: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val bg = if (isLight) Color.rgb(0xF5, 0xF7, 0xFA) else Color.rgb(0x12, 0x12, 0x12)
        val defaultTri = if (isLight) Color.rgb(172, 180, 194) else Color.rgb(208, 214, 224)
        val defaultWire = if (isLight) Color.rgb(0, 121, 107) else Color.rgb(0, 230, 118)
        val color = FloatArray(w * h * 3)
        val bgR = Color.red(bg) / 255f
        val bgG = Color.green(bg) / 255f
        val bgB = Color.blue(bg) / 255f
        for (i in 0 until w * h) {
            color[i * 3] = bgR; color[i * 3 + 1] = bgG; color[i * 3 + 2] = bgB
        }
        val depth = FloatArray(w * h) { Float.MAX_VALUE }
        val mv = FloatArray(16)
        val mvp = FloatArray(16)
        MeshCamera.compute(w, h, yawDeg, pitchDeg, zoom, scene.isYUp, scene.minX, scene.maxX, scene.minY, scene.maxY, scene.minZ, scene.maxZ, mv, mvp)
        val v = scene.vertices
        val polys = min(scene.polygonCount, maxTriangles)
        val textures = scene.textures.map { tex(it) }
        val translucentList = ArrayList<Int>()
        val lines = ArrayList<Int>()
        for (p in 0 until polys) {
            val b = p * 9
            if (b + 8 >= v.size) break
            if (v[b + 6] == v[b] && v[b + 7] == v[b + 1] && v[b + 8] == v[b + 2]) {
                lines.add(p)
                continue
            }
            if (isTranslucent(scene, p, textures, defaultTri)) translucentList.add(p) else drawTriangle(scene, p, mv, mvp, w, h, color, depth, textures, defaultTri, false)
        }
        val order = translucentList.sortedByDescending { p ->
            var z = 0f
            for (k in 0 until 3) {
                val b = p * 9 + k * 3
                z += mv[2] * v[b] + mv[6] * v[b + 1] + mv[10] * v[b + 2] + mv[14]
            }
            -z
        }
        for (p in order) drawTriangle(scene, p, mv, mvp, w, h, color, depth, textures, defaultTri, true)
        for (p in lines) drawLine(scene, p, mvp, w, h, color, depth, defaultWire)
        val out = IntArray(w * h)
        for (i in out.indices) {
            out[i] = Color.rgb(
                (color[i * 3].coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                (color[i * 3 + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                (color[i * 3 + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            )
        }
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
    }

    private fun isTranslucent(scene: MeshScene, p: Int, textures: List<Tex?>, defaultTri: Int): Boolean {
        val ti = texIndex(scene, p)
        if (ti >= 0 && textures.getOrNull(ti)?.translucent == true) return true
        val c = scene.polyColors.getOrElse(p) { 0 }.let { if (it != 0) it else defaultTri }
        if (Color.alpha(c) < 250) return true
        val vc = scene.vertexColors ?: return false
        for (k in 0 until 3) {
            val x = vc.getOrElse(p * 3 + k) { 0 }
            if (x != 0 && Color.alpha(x) < 250) return true
        }
        return false
    }

    private fun texIndex(scene: MeshScene, p: Int): Int {
        val tt = scene.triTextures ?: return -1
        if (scene.uvs == null) return -1
        val t = tt.getOrElse(p) { -1 }
        return if (t in scene.textures.indices && !scene.textures[t].isRecycled) t else -1
    }

    private fun normalTexIndex(scene: MeshScene, p: Int): Int {
        val tt = scene.triNormalTextures ?: return -1
        if (scene.uvs == null) return -1
        val t = tt.getOrElse(p) { -1 }
        return if (t in scene.textures.indices && !scene.textures[t].isRecycled) t else -1
    }

    private class Vert(
        var cx: Float = 0f, var cy: Float = 0f, var cz: Float = 0f, var cw: Float = 0f,
        val view: FloatArray = FloatArray(3), val n: FloatArray = FloatArray(3), val uv: FloatArray = FloatArray(2), val col: FloatArray = FloatArray(4)
    ) {
        fun lerp(a: Vert, b: Vert, t: Float): Vert {
            cx = a.cx + (b.cx - a.cx) * t
            cy = a.cy + (b.cy - a.cy) * t
            cz = a.cz + (b.cz - a.cz) * t
            cw = a.cw + (b.cw - a.cw) * t
            for (i in 0 until 3) {
                view[i] = a.view[i] + (b.view[i] - a.view[i]) * t
                n[i] = a.n[i] + (b.n[i] - a.n[i]) * t
            }
            for (i in 0 until 2) uv[i] = a.uv[i] + (b.uv[i] - a.uv[i]) * t
            for (i in 0 until 4) col[i] = a.col[i] + (b.col[i] - a.col[i]) * t
            return this
        }
    }

    private fun drawTriangle(
        scene: MeshScene, p: Int, mv: FloatArray, mvp: FloatArray, w: Int, h: Int,
        color: FloatArray, depth: FloatArray, textures: List<Tex?>, defaultTri: Int, blend: Boolean
    ) {
        val v = scene.vertices
        val b = p * 9
        val ux = v[b + 3] - v[b]; val uy = v[b + 4] - v[b + 1]; val uz = v[b + 5] - v[b + 2]
        val wx = v[b + 6] - v[b]; val wy = v[b + 7] - v[b + 1]; val wz = v[b + 8] - v[b + 2]
        var fnx = uy * wz - uz * wy
        var fny = uz * wx - ux * wz
        var fnz = ux * wy - uy * wx
        val fl = sqrt(fnx * fnx + fny * fny + fnz * fnz)
        if (fl <= 0f) return
        fnx /= fl; fny /= fl; fnz /= fl
        val ti = texIndex(scene, p)
        val ni = normalTexIndex(scene, p)
        val tex = textures.getOrNull(ti)
        val ntex = textures.getOrNull(ni)
        val base = scene.polyColors.getOrElse(p) { 0 }
        val vn = scene.vertexNormals
        val verts = Array(3) { Vert() }
        for (k in 0 until 3) {
            val x = v[b + k * 3]; val y = v[b + k * 3 + 1]; val z = v[b + k * 3 + 2]
            val vt = verts[k]
            vt.cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
            vt.cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
            vt.cz = mvp[2] * x + mvp[6] * y + mvp[10] * z + mvp[14]
            vt.cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
            vt.view[0] = mv[0] * x + mv[4] * y + mv[8] * z + mv[12]
            vt.view[1] = mv[1] * x + mv[5] * y + mv[9] * z + mv[13]
            vt.view[2] = mv[2] * x + mv[6] * y + mv[10] * z + mv[14]
            var nx = fnx
            var ny = fny
            var nz = fnz
            if (vn != null && (p * 3 + k) * 3 + 2 < vn.size) {
                val ax = vn[(p * 3 + k) * 3]; val ay = vn[(p * 3 + k) * 3 + 1]; val az = vn[(p * 3 + k) * 3 + 2]
                if (ax != 0f || ay != 0f || az != 0f) {
                    if (ax * fnx + ay * fny + az * fnz < 0f) { nx = -ax; ny = -ay; nz = -az } else { nx = ax; ny = ay; nz = az }
                }
            }
            vt.n[0] = mv[0] * nx + mv[4] * ny + mv[8] * nz
            vt.n[1] = mv[1] * nx + mv[5] * ny + mv[9] * nz
            vt.n[2] = mv[2] * nx + mv[6] * ny + mv[10] * nz
            val uvs = scene.uvs
            if ((tex != null || ntex != null) && uvs != null && (p * 3 + k) * 2 + 1 < uvs.size) {
                vt.uv[0] = uvs[(p * 3 + k) * 2]
                vt.uv[1] = uvs[(p * 3 + k) * 2 + 1]
            }
            val vc = scene.vertexColors?.getOrElse(p * 3 + k) { 0 } ?: 0
            val c = when {
                vc != 0 -> vc
                base != 0 -> base
                tex != null -> Color.WHITE
                else -> defaultTri
            }
            vt.col[0] = Color.red(c) / 255f
            vt.col[1] = Color.green(c) / 255f
            vt.col[2] = Color.blue(c) / 255f
            vt.col[3] = Color.alpha(c) / 255f
        }
        var tangent: FloatArray? = null
        var bitangent: FloatArray? = null
        if (ntex != null) {
            val e1 = FloatArray(3) { verts[1].view[it] - verts[0].view[it] }
            val e2 = FloatArray(3) { verts[2].view[it] - verts[0].view[it] }
            val du1 = verts[1].uv[0] - verts[0].uv[0]
            val dv1 = verts[1].uv[1] - verts[0].uv[1]
            val du2 = verts[2].uv[0] - verts[0].uv[0]
            val dv2 = verts[2].uv[1] - verts[0].uv[1]
            val det = du1 * dv2 - du2 * dv1
            if (abs(det) > 1e-12f) {
                val r = 1f / det
                tangent = FloatArray(3) { (e1[it] * dv2 - e2[it] * dv1) * r }
                bitangent = FloatArray(3) { (e2[it] * du1 - e1[it] * du2) * r }
            }
        }
        val poly = clipNear(verts)
        if (poly.size < 3) return
        for (k in 1 until poly.size - 1) {
            rasterize(poly[0], poly[k], poly[k + 1], w, h, color, depth, tex, ntex, tangent, bitangent, blend, ti >= 0)
        }
    }

    private fun clipNear(input: Array<Vert>): List<Vert> {
        val out = ArrayList<Vert>(4)
        for (i in input.indices) {
            val a = input[i]
            val b = input[(i + 1) % input.size]
            val da = a.cz + a.cw
            val db = b.cz + b.cw
            if (da >= 0f) out.add(a)
            if ((da >= 0f) != (db >= 0f)) out.add(Vert().lerp(a, b, da / (da - db)))
        }
        return out
    }

    private fun rasterize(
        a: Vert, b: Vert, c: Vert, w: Int, h: Int, color: FloatArray, depth: FloatArray,
        tex: Tex?, ntex: Tex?, tangent: FloatArray?, bitangent: FloatArray?, blend: Boolean, useTex: Boolean
    ) {
        if (a.cw <= 0f || b.cw <= 0f || c.cw <= 0f) return
        val ia = 1f / a.cw; val ib = 1f / b.cw; val ic = 1f / c.cw
        val ax = (a.cx * ia + 1f) * 0.5f * w; val ay = (a.cy * ia + 1f) * 0.5f * h
        val bx = (b.cx * ib + 1f) * 0.5f * w; val by = (b.cy * ib + 1f) * 0.5f * h
        val cx = (c.cx * ic + 1f) * 0.5f * w; val cy = (c.cy * ic + 1f) * 0.5f * h
        val az = a.cz * ia; val bz = b.cz * ib; val cz = c.cz * ic
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (abs(area) < 1e-9f) return
        val minX = max(0, floor(min(ax, min(bx, cx))).toInt())
        val maxX = min(w - 1, floor(max(ax, max(bx, cx))).toInt())
        val minY = max(0, floor(min(ay, min(by, cy))).toInt())
        val maxY = min(h - 1, floor(max(ay, max(by, cy))).toInt())
        if (minX > maxX || minY > maxY) return
        val invArea = 1f / area
        val sampleBuf = FloatArray(4)
        val nBuf = FloatArray(4)
        val alphaCut = if (useTex && !blend) 0.5f else -1f
        for (py in minY..maxY) {
            val sy = py + 0.5f
            for (px in minX..maxX) {
                val sx = px + 0.5f
                var w0 = ((bx - sx) * (cy - sy) - (by - sy) * (cx - sx)) * invArea
                var w1 = ((cx - sx) * (ay - sy) - (cy - sy) * (ax - sx)) * invArea
                var w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val z = w0 * az + w1 * bz + w2 * cz
                if (z < -1f || z > 1f) continue
                val idx = py * w + px
                if (z >= depth[idx]) continue
                val pw0 = w0 * ia
                val pw1 = w1 * ib
                val pw2 = w2 * ic
                val sum = pw0 + pw1 + pw2
                w0 = pw0 / sum; w1 = pw1 / sum; w2 = pw2 / sum
                var r = w0 * a.col[0] + w1 * b.col[0] + w2 * c.col[0]
                var g = w0 * a.col[1] + w1 * b.col[1] + w2 * c.col[1]
                var bl = w0 * a.col[2] + w1 * b.col[2] + w2 * c.col[2]
                var al = w0 * a.col[3] + w1 * b.col[3] + w2 * c.col[3]
                val u = w0 * a.uv[0] + w1 * b.uv[0] + w2 * c.uv[0]
                val vv = w0 * a.uv[1] + w1 * b.uv[1] + w2 * c.uv[1]
                if (tex != null) {
                    sample(tex, u, vv, sampleBuf)
                    r *= sampleBuf[0]; g *= sampleBuf[1]; bl *= sampleBuf[2]; al *= sampleBuf[3]
                }
                if (al < alphaCut) continue
                var nx = w0 * a.n[0] + w1 * b.n[0] + w2 * c.n[0]
                var ny = w0 * a.n[1] + w1 * b.n[1] + w2 * c.n[1]
                var nz = w0 * a.n[2] + w1 * b.n[2] + w2 * c.n[2]
                var nl = sqrt(nx * nx + ny * ny + nz * nz)
                if (nl > 0f) { nx /= nl; ny /= nl; nz /= nl }
                if (ntex != null && tangent != null && bitangent != null) {
                    sample(ntex, u, vv, nBuf)
                    val mx = nBuf[0] * 2f - 1f
                    val my = nBuf[1] * 2f - 1f
                    val mz = nBuf[2] * 2f - 1f
                    val tdn = tangent[0] * nx + tangent[1] * ny + tangent[2] * nz
                    var tx = tangent[0] - nx * tdn; var ty = tangent[1] - ny * tdn; var tz = tangent[2] - nz * tdn
                    val bdn = bitangent[0] * nx + bitangent[1] * ny + bitangent[2] * nz
                    var qx = bitangent[0] - nx * bdn; var qy = bitangent[1] - ny * bdn; var qz = bitangent[2] - nz * bdn
                    val tl = sqrt(tx * tx + ty * ty + tz * tz)
                    val ql = sqrt(qx * qx + qy * qy + qz * qz)
                    if (tl > 0f && ql > 0f) {
                        tx /= tl; ty /= tl; tz /= tl
                        qx /= ql; qy /= ql; qz /= ql
                        val px2 = tx * mx - qx * my + nx * mz
                        val py2 = ty * mx - qy * my + ny * mz
                        val pz2 = tz * mx - qz * my + nz * mz
                        nl = sqrt(px2 * px2 + py2 * py2 + pz2 * pz2)
                        if (nl > 0f) { nx = px2 / nl; ny = py2 / nl; nz = pz2 / nl }
                    }
                }
                val kx = 0.42f; val ky = 0.64f; val kz = 0.64f
                val kl = sqrt(kx * kx + ky * ky + kz * kz)
                val keyDot = abs((nx * kx + ny * ky + nz * kz) / kl)
                val hemi = 0.5f + 0.5f * ny
                val rim = (1f - abs(nz)).coerceIn(0f, 1f)
                val spec = keyDot.pow(4f) * 0.22f
                val intensity = (0.34f + 0.48f * keyDot + 0.18f * hemi + 0.12f * rim * rim).coerceIn(0.28f, 1.15f)
                r = (r * intensity + spec).coerceIn(0f, 1f)
                g = (g * intensity + spec).coerceIn(0f, 1f)
                bl = (bl * intensity + spec).coerceIn(0f, 1f)
                if (blend) {
                    val aa = al.coerceIn(0f, 1f)
                    color[idx * 3] = r * aa + color[idx * 3] * (1f - aa)
                    color[idx * 3 + 1] = g * aa + color[idx * 3 + 1] * (1f - aa)
                    color[idx * 3 + 2] = bl * aa + color[idx * 3 + 2] * (1f - aa)
                } else {
                    color[idx * 3] = r
                    color[idx * 3 + 1] = g
                    color[idx * 3 + 2] = bl
                    depth[idx] = z
                }
            }
        }
    }

    private fun drawLine(scene: MeshScene, p: Int, mvp: FloatArray, w: Int, h: Int, color: FloatArray, depth: FloatArray, defaultWire: Int) {
        val v = scene.vertices
        val b = p * 9
        val pts = FloatArray(6)
        for (k in 0 until 2) {
            val x = v[b + k * 3]; val y = v[b + k * 3 + 1]; val z = v[b + k * 3 + 2]
            val cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
            if (cw <= 1e-6f) return
            pts[k * 3] = ((mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]) / cw + 1f) * 0.5f * w
            pts[k * 3 + 1] = ((mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]) / cw + 1f) * 0.5f * h
            pts[k * 3 + 2] = (mvp[2] * x + mvp[6] * y + mvp[10] * z + mvp[14]) / cw
        }
        val c = scene.polyColors.getOrElse(p) { 0 }.let { if (it != 0) it else defaultWire }
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val bl = Color.blue(c) / 255f
        val dx = pts[3] - pts[0]
        val dy = pts[4] - pts[1]
        val steps = max(abs(dx), abs(dy)).toInt().coerceIn(1, 20000)
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val x = pts[0] + dx * t
            val y = pts[1] + dy * t
            val z = pts[2] + (pts[5] - pts[2]) * t - 1e-4f
            for (oy in 0..1) for (ox in 0..1) {
                val ix = x.toInt() + ox
                val iy = y.toInt() + oy
                if (ix !in 0 until w || iy !in 0 until h) continue
                val idx = iy * w + ix
                if (z > depth[idx] || z < -1f || z > 1f) continue
                depth[idx] = z
                color[idx * 3] = r; color[idx * 3 + 1] = g; color[idx * 3 + 2] = bl
            }
        }
    }
}
