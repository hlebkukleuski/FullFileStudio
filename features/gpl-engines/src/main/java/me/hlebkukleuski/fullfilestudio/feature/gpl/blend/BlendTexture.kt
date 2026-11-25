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
 * Portions ported and adapted from Blender (https://www.blender.org/, source/blender/blenlib/intern/noise.cc),
 * Copyright (C) 2001-2002 NaN Holding B.V., Copyright (C) 2001-2026 Blender Foundation and Blender Authors,
 * licensed under the GNU General Public License, version 2 or later (GPL-2.0-or-later), and
 * OpenShadingLanguage / Sony Pictures Imageworks procedural noise routines,
 * Copyright (c) 2009-2010 Sony Pictures Imageworks Inc., et al. All Rights Reserved. (BSD-3-Clause).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.blend

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal object BlendNoise {
    private val hash get() = BlendNoiseTables.hash
    private val hashpntf get() = BlendNoiseTables.hashpntf
    private val hashvectf get() = BlendNoiseTables.hashvectf

    private fun lerp(t: Float, a: Float, b: Float) = a + t * (b - a)

    private fun npfade(t: Float) = t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f)

    private fun grad(hv: Int, x: Float, y: Float, z: Float): Float {
        val h = hv and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) v else -v)
    }

    fun newPerlin(x0: Float, y0: Float, z0: Float): Float {
        val h = hash
        var u = floor(x0); var v = floor(y0); var w = floor(z0)
        val xi = u.toInt() and 255
        val yi = v.toInt() and 255
        val zi = w.toInt() and 255
        val x = x0 - u; val y = y0 - v; val z = z0 - w
        u = npfade(x); v = npfade(y); w = npfade(z)
        val a = h[xi] + yi
        val aa = h[a] + zi
        val ab = h[a + 1] + zi
        val b = h[xi + 1] + yi
        val ba = h[b] + zi
        val bb = h[b + 1] + zi
        return lerp(
            w,
            lerp(v, lerp(u, grad(h[aa], x, y, z), grad(h[ba], x - 1, y, z)), lerp(u, grad(h[ab], x, y - 1, z), grad(h[bb], x - 1, y - 1, z))),
            lerp(v, lerp(u, grad(h[aa + 1], x, y, z - 1), grad(h[ba + 1], x - 1, y, z - 1)), lerp(u, grad(h[ab + 1], x, y - 1, z - 1), grad(h[bb + 1], x - 1, y - 1, z - 1)))
        )
    }

    fun newPerlinU(x: Float, y: Float, z: Float) = 0.5f + 0.5f * newPerlin(x, y, z)

    fun orgBlenderNoise(x: Float, y: Float, z: Float): Float {
        val h = hash
        val hv = hashvectf
        val fx = floor(x); val fy = floor(y); val fz = floor(z)
        val ox = x - fx; val oy = y - fy; val oz = z - fz
        val ix = fx.toInt(); val iy = fy.toInt(); val iz = fz.toInt()
        val jx = ox - 1; val jy = oy - 1; val jz = oz - 1
        var cn1 = ox * ox; var cn2 = oy * oy; var cn3 = oz * oz
        var cn4 = jx * jx; var cn5 = jy * jy; var cn6 = jz * jz
        cn1 = 1.0f - 3.0f * cn1 + 2.0f * cn1 * ox
        cn2 = 1.0f - 3.0f * cn2 + 2.0f * cn2 * oy
        cn3 = 1.0f - 3.0f * cn3 + 2.0f * cn3 * oz
        cn4 = 1.0f - 3.0f * cn4 - 2.0f * cn4 * jx
        cn5 = 1.0f - 3.0f * cn5 - 2.0f * cn5 * jy
        cn6 = 1.0f - 3.0f * cn6 - 2.0f * cn6 * jz
        val b00 = h[h[ix and 255] + (iy and 255)]
        val b10 = h[h[(ix + 1) and 255] + (iy and 255)]
        val b01 = h[h[ix and 255] + ((iy + 1) and 255)]
        val b11 = h[h[(ix + 1) and 255] + ((iy + 1) and 255)]
        val b20 = iz and 255
        val b21 = (iz + 1) and 255
        var n = 0.5f
        fun term(i: Float, idx: Int, a: Float, b: Float, c: Float) {
            val o = 3 * h[idx]
            n += i * (hv[o] * a + hv[o + 1] * b + hv[o + 2] * c)
        }
        term(cn1 * cn2 * cn3, b20 + b00, ox, oy, oz)
        term(cn1 * cn2 * cn6, b21 + b00, ox, oy, jz)
        term(cn1 * cn5 * cn3, b20 + b01, ox, jy, oz)
        term(cn1 * cn5 * cn6, b21 + b01, ox, jy, jz)
        term(cn4 * cn2 * cn3, b20 + b10, jx, oy, oz)
        term(cn4 * cn2 * cn6, b21 + b10, jx, oy, jz)
        term(cn4 * cn5 * cn3, b20 + b11, jx, jy, oz)
        term(cn4 * cn5 * cn6, b21 + b11, jx, jy, jz)
        return if (n < 0.0f) 0.0f else if (n > 1.0f) 1.0f else n
    }

    fun orgBlenderNoiseS(x: Float, y: Float, z: Float) = 2.0f * orgBlenderNoise(x, y, z) - 1.0f

    fun perlin(x: Float, y: Float, z: Float): Float {
        val p = BlendNoiseTables.perlinUb
        val g = BlendNoiseTables.perlinV3
        fun setup(v: Float): FloatArray {
            val t = v + 10000.0f
            val b0 = t.toInt() and 255
            val b1 = (b0 + 1) and 255
            val r0 = t - floor(t)
            return floatArrayOf(b0.toFloat(), b1.toFloat(), r0, r0 - 1.0f)
        }
        val sx0 = setup(x); val sy0 = setup(y); val sz0 = setup(z)
        val bx0 = sx0[0].toInt(); val bx1 = sx0[1].toInt(); val rx0 = sx0[2]; val rx1 = sx0[3]
        val by0 = sy0[0].toInt(); val by1 = sy0[1].toInt(); val ry0 = sy0[2]; val ry1 = sy0[3]
        val bz0 = sz0[0].toInt(); val bz1 = sz0[1].toInt(); val rz0 = sz0[2]; val rz1 = sz0[3]
        val i = p[bx0]
        val j = p[bx1]
        val b00 = p[i + by0]; val b10 = p[j + by0]; val b01 = p[i + by1]; val b11 = p[j + by1]
        fun at(q: Int, rx: Float, ry: Float, rz: Float) = rx * g[q * 3] + ry * g[q * 3 + 1] + rz * g[q * 3 + 2]
        fun surve(t: Float) = t * t * (3.0f - 2.0f * t)
        val sx = surve(rx0); val sy = surve(ry0); val sz = surve(rz0)
        var u = at(b00 + bz0, rx0, ry0, rz0)
        var v = at(b10 + bz0, rx1, ry0, rz0)
        var a = lerp(sx, u, v)
        u = at(b01 + bz0, rx0, ry1, rz0)
        v = at(b11 + bz0, rx1, ry1, rz0)
        var b = lerp(sx, u, v)
        val c = lerp(sy, a, b)
        u = at(b00 + bz1, rx0, ry0, rz1)
        v = at(b10 + bz1, rx1, ry0, rz1)
        a = lerp(sx, u, v)
        u = at(b01 + bz1, rx0, ry1, rz1)
        v = at(b11 + bz1, rx1, ry1, rz1)
        b = lerp(sx, u, v)
        val d = lerp(sy, a, b)
        return 1.5f * lerp(sz, c, d)
    }

    fun perlinU(x: Float, y: Float, z: Float) = 0.5f + 0.5f * perlin(x, y, z)

    private fun dist(type: Int, x0: Float, y0: Float, z0: Float, e: Float): Float = when (type) {
        1 -> x0 * x0 + y0 * y0 + z0 * z0
        2 -> abs(x0) + abs(y0) + abs(z0)
        3 -> { val x = abs(x0); val y = abs(y0); val z = abs(z0); val t = if (x > y) x else y; if (z > t) z else t }
        4 -> { val d = sqrt(abs(x0)) + sqrt(abs(y0)) + sqrt(abs(z0)); d * d }
        5 -> { val x = x0 * x0; val y = y0 * y0; val z = z0 * z0; sqrt(sqrt(x * x + y * y + z * z)) }
        6 -> (abs(x0).pow(e) + abs(y0).pow(e) + abs(z0).pow(e)).pow(1.0f / e)
        else -> sqrt(x0 * x0 + y0 * y0 + z0 * z0)
    }

    private fun hashPnt(x: Int, y: Int, z: Int): Int {
        val h = hash
        return 3 * h[(h[(h[z and 255] + y) and 255] + x) and 255]
    }

    fun voronoi(x: Float, y: Float, z: Float, da: FloatArray, pa: FloatArray, me: Float, dtype: Int) {
        val hp = hashpntf
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        da[0] = 1e10f; da[1] = 1e10f; da[2] = 1e10f; da[3] = 1e10f
        for (xx in xi - 1..xi + 1) for (yy in yi - 1..yi + 1) for (zz in zi - 1..zi + 1) {
            val o = hashPnt(xx, yy, zz)
            val px = hp[o] + xx; val py = hp[o + 1] + yy; val pz = hp[o + 2] + zz
            val d = dist(dtype, x - px, y - py, z - pz, me)
            if (d < da[0]) {
                da[3] = da[2]; da[2] = da[1]; da[1] = da[0]; da[0] = d
                for (k in 0 until 3) { pa[9 + k] = pa[6 + k]; pa[6 + k] = pa[3 + k]; pa[3 + k] = pa[k] }
                pa[0] = px; pa[1] = py; pa[2] = pz
            } else if (d < da[1]) {
                da[3] = da[2]; da[2] = da[1]; da[1] = d
                for (k in 0 until 3) { pa[9 + k] = pa[6 + k]; pa[6 + k] = pa[3 + k] }
                pa[3] = px; pa[4] = py; pa[5] = pz
            } else if (d < da[2]) {
                da[3] = da[2]; da[2] = d
                for (k in 0 until 3) pa[9 + k] = pa[6 + k]
                pa[6] = px; pa[7] = py; pa[8] = pz
            } else if (d < da[3]) {
                da[3] = d
                pa[9] = px; pa[10] = py; pa[11] = pz
            }
        }
    }

    private fun vor(x: Float, y: Float, z: Float, k: Int): Float {
        val da = FloatArray(4)
        voronoi(x, y, z, da, FloatArray(12), 1f, 0)
        return if (k < 4) da[k] else da[1] - da[0]
    }

    private fun crackle(x: Float, y: Float, z: Float): Float {
        val t = 10 * vor(x, y, z, 4)
        return if (t > 1.0f) 1.0f else t
    }

    fun cellNoiseU(x0: Float, y0: Float, z0: Float): Float {
        val x = (x0 + 0.000001f) * 1.00001f
        val y = (y0 + 0.000001f) * 1.00001f
        val z = (z0 + 0.000001f) * 1.00001f
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        var n = xi + yi * 1301 + zi * 314159
        n = n xor (n shl 13)
        val r = n * (n * n * 15731 + 789221) + 1376312589
        return (r.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296.0f
    }

    fun cellNoise(x: Float, y: Float, z: Float) = 2.0f * cellNoiseU(x, y, z) - 1.0f

    fun cellV3(x0: Float, y0: Float, z0: Float, out: FloatArray) {
        val x = (x0 + 0.000001f) * 1.00001f
        val y = (y0 + 0.000001f) * 1.00001f
        val z = (z0 + 0.000001f) * 1.00001f
        val o = hashPnt(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
        out[0] = hashpntf[o]; out[1] = hashpntf[o + 1]; out[2] = hashpntf[o + 2]
    }

    private fun unsignedBasis(b: Int): (Float, Float, Float) -> Float = when (b) {
        1 -> ::perlinU
        2 -> ::newPerlinU
        3 -> { x, y, z -> vor(x, y, z, 0) }
        4 -> { x, y, z -> vor(x, y, z, 1) }
        5 -> { x, y, z -> vor(x, y, z, 2) }
        6 -> { x, y, z -> vor(x, y, z, 3) }
        7 -> { x, y, z -> vor(x, y, z, 4) }
        8 -> ::crackle
        14 -> ::cellNoiseU
        else -> ::orgBlenderNoise
    }

    private fun signedBasis(b: Int): (Float, Float, Float) -> Float = when (b) {
        1 -> ::perlin
        2 -> ::newPerlin
        3 -> { x, y, z -> 2.0f * vor(x, y, z, 0) - 1.0f }
        4 -> { x, y, z -> 2.0f * vor(x, y, z, 1) - 1.0f }
        5 -> { x, y, z -> 2.0f * vor(x, y, z, 2) - 1.0f }
        6 -> { x, y, z -> 2.0f * vor(x, y, z, 3) - 1.0f }
        7 -> { x, y, z -> 2.0f * vor(x, y, z, 4) - 1.0f }
        8 -> { x, y, z -> val t = 10 * vor(x, y, z, 4); if (t > 1.0f) 1.0f else 2.0f * t - 1.0f }
        14 -> ::cellNoise
        else -> ::orgBlenderNoiseS
    }

    private fun known(b: Int) = b in 1..8 || b == 14

    fun genericNoise(noisesize0: Float, x0: Float, y0: Float, z0: Float, hard: Boolean, basis: Int): Float {
        val fn = unsignedBasis(basis)
        var x = x0; var y = y0; var z = z0
        if (!known(basis)) { x += 1; y += 1; z += 1 }
        if (noisesize0 != 0.0f) {
            val ns = 1.0f / noisesize0
            x *= ns; y *= ns; z *= ns
        }
        return if (hard) abs(2.0f * fn(x, y, z) - 1.0f) else fn(x, y, z)
    }

    fun genericTurbulence(noisesize0: Float, x0: Float, y0: Float, z0: Float, oct: Int, hard: Boolean, basis: Int): Float {
        val fn = unsignedBasis(basis)
        var x = x0; var y = y0; var z = z0
        if (!known(basis)) { x += 1; y += 1; z += 1 }
        if (noisesize0 != 0.0f) {
            val ns = 1.0f / noisesize0
            x *= ns; y *= ns; z *= ns
        }
        var sum = 0f
        var amp = 1f
        var fscale = 1f
        for (i in 0..oct) {
            var t = fn(fscale * x, fscale * y, fscale * z)
            if (hard) t = abs(2.0f * t - 1.0f)
            sum += t * amp
            amp *= 0.5f
            fscale *= 2.0f
        }
        sum *= (1 shl oct).toFloat() / ((1 shl (oct + 1)) - 1).toFloat()
        return sum
    }

    fun fbm(x0: Float, y0: Float, z0: Float, h: Float, lac: Float, oct: Float, basis: Int): Float {
        val fn = signedBasis(basis)
        var x = x0; var y = y0; var z = z0
        var value = 0.0f
        var pwr = 1.0f
        val pwHL = lac.pow(-h)
        for (i in 0 until oct.toInt()) {
            value += fn(x, y, z) * pwr
            pwr *= pwHL
            x *= lac; y *= lac; z *= lac
        }
        val rmd = oct - floor(oct)
        if (rmd != 0.0f) value += rmd * fn(x, y, z) * pwr
        return value
    }

    fun multiFractal(x0: Float, y0: Float, z0: Float, h: Float, lac: Float, oct: Float, basis: Int): Float {
        val fn = signedBasis(basis)
        var x = x0; var y = y0; var z = z0
        var value = 1.0f
        var pwr = 1.0f
        val pwHL = lac.pow(-h)
        for (i in 0 until oct.toInt()) {
            value *= (pwr * fn(x, y, z) + 1.0f)
            pwr *= pwHL
            x *= lac; y *= lac; z *= lac
        }
        val rmd = oct - floor(oct)
        if (rmd != 0.0f) value *= (rmd * fn(x, y, z) * pwr + 1.0f)
        return value
    }

    fun heteroTerrain(x0: Float, y0: Float, z0: Float, h: Float, lac: Float, oct: Float, offset: Float, basis: Int): Float {
        val fn = signedBasis(basis)
        var x = x0; var y = y0; var z = z0
        var value = offset + fn(x, y, z)
        x *= lac; y *= lac; z *= lac
        val pwHL = lac.pow(-h)
        var pwr = pwHL
        for (i in 1 until oct.toInt()) {
            val inc = (fn(x, y, z) + offset) * pwr * value
            value += inc
            pwr *= pwHL
            x *= lac; y *= lac; z *= lac
        }
        val rmd = oct - floor(oct)
        if (rmd != 0.0f) {
            val inc = (fn(x, y, z) + offset) * pwr * value
            value += rmd * inc
        }
        return value
    }

    fun hybridMultiFractal(x0: Float, y0: Float, z0: Float, h: Float, lac: Float, oct: Float, offset: Float, gain: Float, basis: Int): Float {
        val fn = signedBasis(basis)
        var x = x0; var y = y0; var z = z0
        var result = fn(x, y, z) + offset
        var weight = gain * result
        x *= lac; y *= lac; z *= lac
        val pwHL = lac.pow(-h)
        var pwr = pwHL
        var i = 1
        while (weight > 0.001f && i < oct.toInt()) {
            weight = minOf(weight, 1.0f)
            val signal = (fn(x, y, z) + offset) * pwr
            pwr *= pwHL
            result += weight * signal
            weight *= gain * signal
            x *= lac; y *= lac; z *= lac
            i++
        }
        val rmd = oct - floor(oct)
        if (rmd != 0.0f) result += rmd * ((fn(x, y, z) + offset) * pwr)
        return result
    }

    fun ridgedMultiFractal(x0: Float, y0: Float, z0: Float, h: Float, lac: Float, oct: Float, offset: Float, gain: Float, basis: Int): Float {
        val fn = signedBasis(basis)
        var x = x0; var y = y0; var z = z0
        var signal = (offset - abs(fn(x, y, z))).pow(2)
        var result = signal
        val pwHL = lac.pow(-h)
        var pwr = pwHL
        for (i in 1 until oct.toInt()) {
            x *= lac; y *= lac; z *= lac
            var weight = signal * gain
            if (weight > 1.0f) weight = 1.0f else if (weight < 0.0f) weight = 0.0f
            signal = offset - abs(fn(x, y, z))
            signal *= signal
            signal *= weight
            result += signal * pwr
            pwr *= pwHL
        }
        return result
    }

    fun variableLacunarity(x: Float, y: Float, z: Float, distortion: Float, b1: Int, b2: Int): Float {
        val f1 = signedBasis(b1)
        val f2 = signedBasis(b2)
        val r0 = f1(x + 13.5f, y + 13.5f, z + 13.5f) * distortion
        val r1 = f1(x, y, z) * distortion
        val r2 = f1(x - 13.5f, y - 13.5f, z - 13.5f) * distortion
        return f2(x + r0, y + r1, z + r2)
    }
}

internal class BlendImagePixels(val width: Int, val height: Int, val rgba: ByteArray?, val floats: FloatArray? = null)

internal class BlendColorBand(val ipotype: Int, val hue: Int, val mode: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray, val a: FloatArray, val pos: FloatArray) {
    val tot get() = pos.size

    private fun weights(t: Float, cardinal: Boolean): FloatArray {
        val t2 = t * t
        val t3 = t2 * t
        return if (cardinal) {
            val fc = 0.71f
            floatArrayOf(-fc * t3 + 2.0f * fc * t2 - fc * t, (2.0f - fc) * t3 + (fc - 3.0f) * t2 + 1.0f, (fc - 2.0f) * t3 + (3.0f - 2.0f * fc) * t2 + fc * t, fc * t3 - fc * t2)
        } else floatArrayOf(-0.16666666f * t3 + 0.5f * t2 - 0.5f * t + 0.16666666f, 0.5f * t3 - t2 + 0.66666666f, -0.5f * t3 + 0.5f * t2 + 0.5f * t + 0.16666666f, 0.16666666f * t3)
    }

    private fun hueInterp(mfac: Float, fac: Float, h10: Float, h20: Float): Float {
        fun mod(h: Float) = if (h < 1.0f) h else h - 1.0f
        val h1 = mod(h10)
        val h2 = mod(h20)
        val m = when (hue) {
            0 -> if (h1 < h2 && h2 - h1 > 0.5f) 1 else if (h1 > h2 && h2 - h1 < -0.5f) 2 else 0
            1 -> if (h1 == h2) 1 else if (h1 < h2 && h2 - h1 < 0.5f) 1 else if (h1 > h2 && h2 - h1 > -0.5f) 2 else 0
            3 -> if (h1 > h2) 2 else 0
            2 -> if (h1 < h2) 1 else 0
            else -> 0
        }
        return when (m) {
            1 -> mod(mfac * (h1 + 1.0f) + fac * h2)
            2 -> mod(mfac * h1 + fac * (h2 + 1.0f))
            else -> mfac * h1 + fac * h2
        }
    }

    fun evaluate(input: Float, out: FloatArray): Boolean {
        val n = tot
        if (n == 0) return false
        val ipo = if (mode == 0) ipotype else 0
        fun set(i: Int) { out[0] = r[i]; out[1] = g[i]; out[2] = b[i]; out[3] = a[i] }
        if (n == 1) { set(0); return true }
        if (input <= pos[0] && ipo in intArrayOf(0, 1, 4)) { set(0); return true }
        var a1 = 0
        while (a1 < n && pos[a1] <= input) a1++
        val i1: Int
        val i2: Int
        val p1: Float
        val p2: Float
        if (a1 == n) { i2 = n - 1; i1 = n - 1; p2 = pos[i2]; p1 = 1.0f } else if (a1 == 0) { i1 = 0; i2 = 0; p1 = pos[0]; p2 = 0.0f } else { i1 = a1; i2 = a1 - 1; p1 = pos[i1]; p2 = pos[i2] }
        if (a1 == n && ipo in intArrayOf(0, 1, 4)) { set(i2); return true }
        if (ipo == 4) { set(i2); return true }
        var fac = if (p2 != p1) (input - p1) / (p2 - p1) else if (a1 != n) 0.0f else 1.0f
        if (ipo == 2 || ipo == 3) {
            val i0 = if (a1 >= n - 1) i1 else i1 + 1
            val i3 = if (a1 < 2) i2 else i2 - 1
            fac = fac.coerceIn(0.0f, 1.0f)
            val t = weights(fac, ipo == 3)
            out[0] = t[3] * r[i3] + t[2] * r[i2] + t[1] * r[i1] + t[0] * r[i0]
            out[1] = t[3] * g[i3] + t[2] * g[i2] + t[1] * g[i1] + t[0] * g[i0]
            out[2] = t[3] * b[i3] + t[2] * b[i2] + t[1] * b[i1] + t[0] * b[i0]
            out[3] = t[3] * a[i3] + t[2] * a[i2] + t[1] * a[i1] + t[0] * a[i0]
            for (k in 0 until 4) out[k] = out[k].coerceIn(0.0f, 1.0f)
            return true
        }
        if (ipo == 1) {
            val f2 = fac * fac
            fac = 3.0f * f2 - 2.0f * f2 * fac
        }
        val mfac = 1.0f - fac
        when (mode) {
            1 -> {
                val c1 = BlendColor.rgbToHsv(r[i1], g[i1], b[i1])
                val c2 = BlendColor.rgbToHsv(r[i2], g[i2], b[i2])
                val h = hueInterp(mfac, fac, c1[0], c2[0])
                val rgb = BlendColor.hsvToRgb(h, mfac * c1[1] + fac * c2[1], mfac * c1[2] + fac * c2[2])
                out[0] = rgb[0]; out[1] = rgb[1]; out[2] = rgb[2]
            }
            2 -> {
                val c1 = BlendColor.rgbToHsl(r[i1], g[i1], b[i1])
                val c2 = BlendColor.rgbToHsl(r[i2], g[i2], b[i2])
                val h = hueInterp(mfac, fac, c1[0], c2[0])
                val rgb = BlendColor.hslToRgb(h, mfac * c1[1] + fac * c2[1], mfac * c1[2] + fac * c2[2])
                out[0] = rgb[0]; out[1] = rgb[1]; out[2] = rgb[2]
            }
            else -> {
                out[0] = mfac * r[i1] + fac * r[i2]
                out[1] = mfac * g[i1] + fac * g[i2]
                out[2] = mfac * b[i1] + fac * b[i2]
            }
        }
        out[3] = mfac * a[i1] + fac * a[i2]
        return true
    }
}

internal object BlendColor {
    fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
        val nr = (abs(h * 6.0f - 3.0f) - 1.0f).coerceIn(0f, 1f)
        val ng = (2.0f - abs(h * 6.0f - 2.0f)).coerceIn(0f, 1f)
        val nb = (2.0f - abs(h * 6.0f - 4.0f)).coerceIn(0f, 1f)
        return floatArrayOf(((nr - 1.0f) * s + 1.0f) * v, ((ng - 1.0f) * s + 1.0f) * v, ((nb - 1.0f) * s + 1.0f) * v)
    }

    fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        val nr = (abs(h * 6.0f - 3.0f) - 1.0f).coerceIn(0f, 1f)
        val ng = (2.0f - abs(h * 6.0f - 2.0f)).coerceIn(0f, 1f)
        val nb = (2.0f - abs(h * 6.0f - 4.0f)).coerceIn(0f, 1f)
        val chroma = (1.0f - abs(2.0f * l - 1.0f)) * s
        return floatArrayOf((nr - 0.5f) * chroma + l, (ng - 0.5f) * chroma + l, (nb - 0.5f) * chroma + l)
    }

    fun rgbToHsv(r0: Float, g0: Float, b0: Float): FloatArray {
        var r = r0; var g = g0; var b = b0
        var k = 0.0f
        if (g < b) { val t = g; g = b; b = t; k = -1.0f }
        var minGb = b
        if (r < g) { val t = r; r = g; g = t; k = -2.0f / 6.0f - k; minGb = minOf(g, b) }
        val chroma = r - minGb
        return floatArrayOf(abs(k + (g - b) / (6.0f * chroma + 1e-20f)), chroma / (r + 1e-20f), r)
    }

    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val cmax = maxOf(r, g, b)
        val cmin = minOf(r, g, b)
        var h: Float
        val s: Float
        val l = minOf(1.0f, (cmax + cmin) / 2.0f)
        if (cmax == cmin) { h = 0f; s = 0f } else {
            val d = cmax - cmin
            s = if (l > 0.5f) d / (2.0f - cmax - cmin) else d / (cmax + cmin)
            h = when (cmax) {
                r -> (g - b) / d + (if (g < b) 6.0f else 0.0f)
                g -> (b - r) / d + 2.0f
                else -> (r - g) / d + 4.0f
            }
        }
        h /= 6.0f
        return floatArrayOf(h, s, l)
    }
}

internal class BlendTexture(
    val type: Int, val stype: Int, val flag: Int, val imaflag: Int, val extend: Int,
    val noisesize: Float, val turbul: Float, val bright: Float, val contrast: Float, val saturation: Float,
    val rfac: Float, val gfac: Float, val bfac: Float, val filtersize: Float,
    val mgH: Float, val mgLacunarity: Float, val mgOctaves: Float, val mgOffset: Float, val mgGain: Float,
    val distAmount: Float, val nsOutscale: Float, val vnW: FloatArray, val vnMexp: Float, val vnDistm: Int, val vnColtype: Int,
    val noisedepth: Int, val noisetype: Int, val noisebasis: Int, val noisebasis2: Int,
    val crop: FloatArray, val xrepeat: Int, val yrepeat: Int, val checkerdist: Float,
    val colorband: BlendColorBand?, val image: BlendImagePixels?, val imageAlphaIgnore: Boolean,
    val fileVersion: Int = 500, val texfilter: Int = 0
) {
    class Result {
        var tin = 0f
        val rgba = FloatArray(4)
        var talpha = false
    }

    private var rng = 0L

    private fun bricont(r: Result) {
        r.tin = (r.tin - 0.5f) * contrast + bright - 0.5f
        if (flag and (1 shl 10) == 0) {
            if (r.tin < 0.0f) r.tin = 0.0f else if (r.tin > 1.0f) r.tin = 1.0f
        }
    }

    private fun bricontRgb(r: Result) {
        val c = r.rgba
        c[0] = rfac * ((c[0] - 0.5f) * contrast + bright - 0.5f)
        c[1] = gfac * ((c[1] - 0.5f) * contrast + bright - 0.5f)
        c[2] = bfac * ((c[2] - 0.5f) * contrast + bright - 0.5f)
        val noClamp = flag and (1 shl 10) != 0
        if (!noClamp) for (k in 0 until 3) if (c[k] < 0.0f) c[k] = 0.0f
        if (saturation != 1.0f) {
            val hsv = BlendColor.rgbToHsv(c[0], c[1], c[2])
            hsv[1] *= saturation
            val rgb = BlendColor.hsvToRgb(hsv[0], hsv[1], hsv[2])
            c[0] = rgb[0]; c[1] = rgb[1]; c[2] = rgb[2]
            if (saturation > 1.0f && !noClamp) for (k in 0 until 3) if (c[k] < 0.0f) c[k] = 0.0f
        }
    }

    private val hard get() = noisetype != 0

    private fun blendTex(v: FloatArray, r: Result): Int {
        val x: Float
        val y: Float
        if (flag and 2 != 0) { x = v[1]; y = v[0] } else { x = v[0]; y = v[1] }
        r.tin = when (stype) {
            0 -> (1.0f + x) / 2.0f
            1 -> { val t = (1.0f + x) / 2.0f; if (t < 0.0f) 0.0f else t * t }
            2 -> { val t = (1.0f + x) / 2.0f; if (t <= 0.0f) 0.0f else if (t >= 1.0f) 1.0f else { val t2 = t * t; 3.0f * t2 - 2.0f * t2 * t } }
            3 -> (2.0f + x + y) / 4.0f
            6 -> atan2(y, x) / (2 * Math.PI).toFloat() + 0.5f
            else -> {
                var t = 1.0f - sqrt(x * x + y * y + v[2] * v[2])
                t = maxOf(t, 0.0f)
                if (stype == 5) t *= t
                t
            }
        }
        bricont(r)
        return 0
    }

    private fun clouds(v: FloatArray, r: Result): Int {
        r.tin = BlendNoise.genericTurbulence(noisesize, v[0], v[1], v[2], noisedepth, hard, noisebasis)
        if (stype == 1) {
            r.rgba[0] = r.tin
            r.rgba[1] = BlendNoise.genericTurbulence(noisesize, v[1], v[0], v[2], noisedepth, hard, noisebasis)
            r.rgba[2] = BlendNoise.genericTurbulence(noisesize, v[1], v[2], v[0], noisedepth, hard, noisebasis)
            bricontRgb(r)
            r.rgba[3] = 1.0f
            return 1
        }
        bricont(r)
        return 0
    }

    private fun wave(wf: Int, a0: Float): Float {
        val b = (2 * Math.PI).toFloat()
        return when (wf) {
            1 -> {
                var a = a0
                val n = (a / b).toInt()
                a -= n * b
                if (a < 0) a += b
                a / b
            }
            2 -> 1.0f - 2.0f * abs(floor((a0 * (1.0f / b)) + 0.5f) - (a0 * (1.0f / b)))
            else -> 0.5f + 0.5f * sin(a0)
        }
    }

    private fun woodInt(x: Float, y: Float, z: Float): Float {
        val wf = if (noisebasis2 > 2 || noisebasis2 < 0) 0 else noisebasis2
        return when (stype) {
            0 -> wave(wf, (x + y + z) * 10.0f)
            1 -> wave(wf, sqrt(x * x + y * y + z * z) * 20.0f)
            2 -> wave(wf, (x + y + z) * 10.0f + turbul * BlendNoise.genericNoise(noisesize, x, y, z, hard, noisebasis))
            3 -> wave(wf, sqrt(x * x + y * y + z * z) * 20.0f + turbul * BlendNoise.genericNoise(noisesize, x, y, z, hard, noisebasis))
            else -> 0f
        }
    }

    private fun marbleInt(x: Float, y: Float, z: Float): Float {
        val wf = if (noisebasis2 > 2 || noisebasis2 < 0) 0 else noisebasis2
        val n = 5.0f * (x + y + z)
        var mi = n + turbul * BlendNoise.genericTurbulence(noisesize, x, y, z, noisedepth, hard, noisebasis)
        if (stype >= 0) {
            mi = wave(wf, mi)
            if (stype == 1) mi = sqrt(mi) else if (stype == 2) mi = sqrt(sqrt(mi))
        }
        return mi
    }

    private fun magic(v: FloatArray, r: Result): Int {
        val n = noisedepth
        var turb = turbul / 5.0f
        var x = sin((v[0] + v[1] + v[2]) * 5.0f)
        var y = cos((-v[0] + v[1] - v[2]) * 5.0f)
        var z = -cos((-v[0] - v[1] + v[2]) * 5.0f)
        if (n > 0) {
            x *= turb; y *= turb; z *= turb
            y = -cos(x - y + z); y *= turb
            if (n > 1) {
                x = cos(x - y - z); x *= turb
                if (n > 2) {
                    z = sin(-x - y - z); z *= turb
                    if (n > 3) {
                        x = -cos(-x + y - z); x *= turb
                        if (n > 4) {
                            y = -sin(-x + y + z); y *= turb
                            if (n > 5) {
                                y = -cos(-x + y + z); y *= turb
                                if (n > 6) {
                                    x = cos(x + y + z); x *= turb
                                    if (n > 7) {
                                        z = sin(x + y - z); z *= turb
                                        if (n > 8) {
                                            x = -cos(-x - y + z); x *= turb
                                            if (n > 9) { y = -sin(x - y + z); y *= turb }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (turb != 0.0f) {
            turb *= 2.0f
            x /= turb; y /= turb; z /= turb
        }
        r.rgba[0] = 0.5f - x; r.rgba[1] = 0.5f - y; r.rgba[2] = 0.5f - z
        r.tin = (1.0f / 3.0f) * (r.rgba[0] + r.rgba[1] + r.rgba[2])
        bricontRgb(r)
        r.rgba[3] = 1.0f
        return 1
    }

    private fun stucci(v: FloatArray, r: Result): Int {
        val b2 = BlendNoise.genericNoise(noisesize, v[0], v[1], v[2], hard, noisebasis)
        var ofs = turbul / 200.0f
        if (stype != 0) ofs *= b2 * b2
        r.tin = BlendNoise.genericNoise(noisesize, v[0], v[1], v[2] + ofs, hard, noisebasis)
        if (stype == 2) r.tin = 1.0f - r.tin
        r.tin = maxOf(r.tin, 0.0f)
        return 0
    }

    private fun texNoise(r: Result): Int {
        rng = (rng * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
        val ran = (rng ushr 17).toInt()
        var div = 3.0f
        var shift = 29
        var v = (ran shr shift) and 3
        var loop = noisedepth
        while (loop-- > 0) {
            shift -= 2
            v *= (ran shr shift) and 3
            div *= 3.0f
        }
        r.tin = v.toFloat() / div
        bricont(r)
        return 0
    }

    private fun voronoiTex(v: FloatArray, r: Result): Int {
        val da = FloatArray(4)
        val pa = FloatArray(12)
        val aw = FloatArray(4) { abs(vnW[it]) }
        var sc = aw[0] + aw[1] + aw[2] + aw[3]
        if (sc != 0.0f) sc = nsOutscale / sc
        BlendNoise.voronoi(v[0], v[1], v[2], da, pa, vnMexp, vnDistm)
        r.tin = sc * abs(vnW[0] * da[0] + vnW[1] * da[1] + vnW[2] * da[2] + vnW[3] * da[3])
        val isColor = vnColtype in 1..3
        if (isColor) {
            val ca = FloatArray(3)
            r.rgba[0] = 0f; r.rgba[1] = 0f; r.rgba[2] = 0f
            for (k in 0 until 4) {
                BlendNoise.cellV3(pa[k * 3], pa[k * 3 + 1], pa[k * 3 + 2], ca)
                if (k == 0) { r.rgba[0] = aw[0] * ca[0]; r.rgba[1] = aw[0] * ca[1]; r.rgba[2] = aw[0] * ca[2] }
                else { r.rgba[0] += aw[k] * ca[0]; r.rgba[1] += aw[k] * ca[1]; r.rgba[2] += aw[k] * ca[2] }
            }
            if (vnColtype == 2 || vnColtype == 3) {
                var t1 = (da[1] - da[0]) * 10
                t1 = minOf(t1, 1f)
                if (vnColtype == 3) t1 *= r.tin else t1 *= sc
                for (k in 0 until 3) r.rgba[k] *= t1
            } else for (k in 0 until 3) r.rgba[k] *= sc
            bricontRgb(r)
            r.rgba[3] = 1.0f
            return 1
        }
        bricont(r)
        return 0
    }

    private fun pixel(x: Int, y: Int, out: FloatArray) {
        val img = image!!
        val o = (y * img.width + x) * 4
        val fl = img.floats
        if (fl != null) {
            out[0] = fl[o]; out[1] = fl[o + 1]; out[2] = fl[o + 2]; out[3] = fl[o + 3]
            return
        }
        val b = img.rgba!!
        val a = (b[o + 3].toInt() and 0xFF).toFloat() * (1.0f / 255.0f)
        out[0] = (b[o].toInt() and 0xFF).toFloat() * (1.0f / 255.0f) * a
        out[1] = (b[o + 1].toInt() and 0xFF).toFloat() * (1.0f / 255.0f) * a
        out[2] = (b[o + 2].toInt() and 0xFF).toFloat() * (1.0f / 255.0f) * a
        out[3] = a
    }

    private fun boxClip(xmin: Float, xmax: Float, ymin: Float, ymax: Float, out: FloatArray) {
        val img = image!!
        var sx = floor(xmin).toInt(); var ex = floor(xmax).toInt()
        var sy = floor(ymin).toInt(); var ey = floor(ymax).toInt()
        sx = maxOf(sx, 0); sy = maxOf(sy, 0)
        if (ex >= img.width) ex = img.width - 1
        if (ey >= img.height) ey = img.height - 1
        if (sy == ey && sx == ex) { pixel(sx, sy, out); return }
        val col = FloatArray(4)
        out.fill(0f)
        var div = 0f
        for (y in sy..ey) {
            var muly = 1.0f
            if (sy != ey) {
                if (y == sy) muly = 1.0f - (ymin - y)
                if (y == ey) muly = ymax - y
            }
            if (sx == ex) {
                pixel(sx, y, col)
                for (k in 0 until 4) out[k] += col[k] * muly
                div += muly
            } else for (x in sx..ex) {
                var mulx = muly
                if (x == sx) mulx *= 1.0f - (xmin - x)
                if (x == ex) mulx *= xmax - x
                pixel(x, y, col)
                if (mulx == 1.0f) { for (k in 0 until 4) out[k] += col[k]; div += 1.0f }
                else { for (k in 0 until 4) out[k] += col[k] * mulx; div += mulx }
            }
        }
        if (div != 0.0f) { val d = 1.0f / div; for (k in 0 until 4) out[k] *= d } else out.fill(0f)
    }

    private fun boxSample(minx: Float, miny: Float, maxx: Float, maxy: Float, r: Result, repeat: Boolean, extendMode: Boolean) {
        val img = image!!
        val w = img.width.toFloat()
        val h = img.height.toFloat()
        val stack = ArrayList<FloatArray>()
        stack.add(floatArrayOf(minx * w, maxx * w, miny * h, maxy * h))
        var alphaclip = 1.0f
        fun swap(axis: Int, lo: Float, hi: Float) {
            val n = stack.size
            for (i in 0 until n) {
                val rf = stack[i]
                val mn = axis * 2
                val mx = mn + 1
                if (rf[mn] < lo) {
                    if (rf[mx] < lo) { rf[mn] += hi - lo; rf[mx] += hi - lo } else {
                        rf[mx] = minOf(rf[mx], hi)
                        val nr = rf.copyOf()
                        nr[mx] = hi
                        nr[mn] = rf[mn] + (hi - lo)
                        if (nr[mn] != nr[mx]) stack.add(nr)
                        rf[mn] = lo
                    }
                } else if (rf[mx] > hi) {
                    if (rf[mn] > hi) { rf[mn] -= hi - lo; rf[mx] -= hi - lo } else {
                        rf[mn] = maxOf(rf[mn], lo)
                        val nr = rf.copyOf()
                        nr[mn] = lo
                        nr[mx] = rf[mx] - (hi - lo)
                        if (nr[mn] != nr[mx]) stack.add(nr)
                        rf[mx] = hi
                    }
                }
            }
        }
        fun clip(axis: Int, lo: Float, hi: Float): Float {
            val rf = stack[0]
            val mn = axis * 2
            val mx = mn + 1
            val size = rf[mx] - rf[mn]
            rf[mn] = maxOf(rf[mn], lo)
            rf[mx] = minOf(rf[mx], hi)
            if (rf[mn] > rf[mx]) { rf[mn] = rf[mx]; return 0.0f }
            return if (size != 0.0f) (rf[mx] - rf[mn]) / size else 1.0f
        }
        if (extendMode) {
            stack[0][0] = stack[0][0].coerceIn(0.0f, w - 1); stack[0][1] = stack[0][1].coerceIn(0.0f, w - 1)
        } else if (repeat) swap(0, 0.0f, w) else {
            alphaclip = clip(0, 0.0f, w)
            if (alphaclip <= 0.0f) { r.rgba.fill(0f); return }
        }
        if (extendMode) {
            stack[0][2] = stack[0][2].coerceIn(0.0f, h - 1); stack[0][3] = stack[0][3].coerceIn(0.0f, h - 1)
        } else if (repeat) swap(1, 0.0f, h) else {
            alphaclip *= clip(1, 0.0f, h)
            if (alphaclip <= 0.0f) { r.rgba.fill(0f); return }
        }
        if (stack.size > 1) {
            var tot = 0f
            r.rgba.fill(0f)
            val tmp = FloatArray(4)
            for (rf in stack) {
                boxClip(rf[0], rf[1], rf[2], rf[3], tmp)
                val opp = (rf[1] - rf[0]) * (rf[3] - rf[2])
                tot += opp
                for (k in 0 until 3) r.rgba[k] += opp * tmp[k]
                if (r.talpha) r.rgba[3] += opp * tmp[3]
            }
            if (tot != 0.0f) {
                for (k in 0 until 3) r.rgba[k] /= tot
                if (r.talpha) r.rgba[3] /= tot
            }
        } else {
            val rf = stack[0]
            boxClip(rf[0], rf[1], rf[2], rf[3], r.rgba)
        }
        if (!r.talpha) r.rgba[3] = 1.0f
        if (alphaclip != 1.0f) for (k in 0 until 4) r.rgba[k] *= alphaclip
    }

    private fun imageWrap(v: FloatArray, r: Result): Int {
        r.tin = 0f; r.rgba.fill(0f)
        val img = image ?: return 1
        var fx = v[0]
        var fy = v[1]
        if (imaflag and (1 shl 4) != 0) { val t = fx; fx = fy; fy = t }
        if (extend == 5) {
            val xs = floor(fx).toInt()
            val ys = floor(fy).toInt()
            fx -= xs; fy -= ys
            if (flag and (1 shl 3) == 0 && (xs + ys) and 1 == 0) return 1
            if (flag and (1 shl 4) == 0 && (xs + ys) and 1 != 0) return 1
            if (checkerdist < 1.0f) {
                fx = (fx - 0.5f) / (1.0f - checkerdist) + 0.5f
                fy = (fy - 0.5f) / (1.0f - checkerdist) + 0.5f
            }
        }
        val xi = floor(fx * img.width).toInt()
        val yi = floor(fy * img.height).toInt()
        var x = xi
        var y = yi
        if (extend == 4) {
            if (x < 0 || y < 0 || x >= img.width || y >= img.height || v[2] < -1.0f || v[2] > 1.0f) return 1
        } else if (extend == 2 || extend == 5) {
            if (x < 0 || y < 0 || x >= img.width || y >= img.height) return 1
        } else {
            if (extend == 1) x = x.coerceIn(0, img.width - 1) else { x %= img.width; if (x < 0) x += img.width }
            if (extend == 1) y = y.coerceIn(0, img.height - 1) else { y %= img.height; if (y < 0) y += img.height }
        }
        if (imaflag and 2 != 0 && !imageAlphaIgnore && imaflag and (1 shl 5) == 0) r.talpha = true
        if (imaflag and 1 != 0) {
            val filterx = (0.5f * filtersize) / img.width
            val filtery = (0.5f * filtersize) / img.height
            if (extend == 1 && fileVersion >= 502) { fx = fx.coerceIn(0.0f, 1.0f); fy = fy.coerceIn(0.0f, 1.0f) } else {
                fx -= (xi - x).toFloat() / img.width.toFloat()
                fy -= (yi - y).toFloat() / img.height.toFloat()
            }
            boxSample(fx - filterx, fy - filtery, fx + filterx, fy + filtery, r, extend == 3, extend == 1)
        } else pixel(x, y, r.rgba)
        if (r.talpha) r.tin = r.rgba[3]
        else if (imaflag and (1 shl 5) != 0) { r.tin = maxOf(r.rgba[0], r.rgba[1], r.rgba[2]); r.rgba[3] = r.tin }
        else { r.tin = 1.0f; r.rgba[3] = 1.0f }
        if (flag and 4 != 0) r.rgba[3] = 1.0f - r.rgba[3]
        if (r.rgba[3] != 1.0f && r.rgba[3] > 1e-4f && imaflag and (1 shl 5) == 0) {
            val inv = 1.0f / r.rgba[3]
            r.rgba[0] *= inv; r.rgba[1] *= inv; r.rgba[2] *= inv
        }
        bricontRgb(r)
        return 1
    }

    private fun map2d(v: FloatArray): FloatArray {
        var fx = (v[0] + 1.0f) / 2.0f
        var fy = (v[1] + 1.0f) / 2.0f
        val wrap = fileVersion >= 500 || texfilter == 0
        if (extend == 3) {
            if (xrepeat > 1 && !wrap) fx *= xrepeat
            if (yrepeat > 1 && !wrap) fy *= yrepeat
            if (xrepeat > 1 && wrap) {
                fx *= xrepeat
                val origf = fx
                if (fx > 1.0f) fx -= fx.toInt() else if (fx < 0.0f) fx += 1 - fx.toInt()
                if (flag and (1 shl 7) != 0 && floor(origf).toInt() and 1 != 0) fx = 1.0f - fx
            }
            if (yrepeat > 1 && wrap) {
                fy *= yrepeat
                val origf = fy
                if (fy > 1.0f) fy -= fy.toInt() else if (fy < 0.0f) fy += 1 - fy.toInt()
                if (flag and (1 shl 8) != 0 && floor(origf).toInt() and 1 != 0) fy = 1.0f - fy
            }
        }
        if (crop[0] != 0.0f || crop[2] != 1.0f) fx = crop[0] + fx * (crop[2] - crop[0])
        if (crop[1] != 0.0f || crop[3] != 1.0f) fy = crop[1] + fy * (crop[3] - crop[1])
        return floatArrayOf(fx, fy, v[2])
    }

    fun evaluate(texco: FloatArray, r: Result) {
        r.talpha = false
        r.tin = 0f
        r.rgba.fill(0f)
        var rv = 0
        when (type) {
            1 -> rv = clouds(texco, r)
            2 -> { r.tin = woodInt(texco[0], texco[1], texco[2]); bricont(r) }
            3 -> { r.tin = marbleInt(texco[0], texco[1], texco[2]); bricont(r) }
            4 -> rv = magic(texco, r)
            5 -> rv = blendTex(texco, r)
            6 -> rv = stucci(texco, r)
            7 -> rv = texNoise(r)
            8 -> rv = imageWrap(map2d(texco), r)
            11 -> {
                val t = floatArrayOf(texco[0] * (1.0f / noisesize), texco[1] * (1.0f / noisesize), texco[2] * (1.0f / noisesize))
                r.tin = when (stype) {
                    0 -> nsOutscale * BlendNoise.multiFractal(t[0], t[1], t[2], mgH, mgLacunarity, mgOctaves, noisebasis)
                    3 -> nsOutscale * BlendNoise.fbm(t[0], t[1], t[2], mgH, mgLacunarity, mgOctaves, noisebasis)
                    1 -> nsOutscale * BlendNoise.ridgedMultiFractal(t[0], t[1], t[2], mgH, mgLacunarity, mgOctaves, mgOffset, mgGain, noisebasis)
                    2 -> nsOutscale * BlendNoise.hybridMultiFractal(t[0], t[1], t[2], mgH, mgLacunarity, mgOctaves, mgOffset, mgGain, noisebasis)
                    4 -> nsOutscale * BlendNoise.heteroTerrain(t[0], t[1], t[2], mgH, mgLacunarity, mgOctaves, mgOffset, noisebasis)
                    else -> 0f
                }
                if (stype in 0..4) bricont(r)
            }
            12 -> rv = voronoiTex(floatArrayOf(texco[0] * (1.0f / noisesize), texco[1] * (1.0f / noisesize), texco[2] * (1.0f / noisesize)), r)
            13 -> {
                r.tin = BlendNoise.variableLacunarity(texco[0] * (1.0f / noisesize), texco[1] * (1.0f / noisesize), texco[2] * (1.0f / noisesize), distAmount, noisebasis, noisebasis2)
                bricont(r)
            }
            else -> { r.tin = 0f; return }
        }
        if (flag and 1 != 0) {
            val col = FloatArray(4)
            if (colorband?.evaluate(r.tin, col) == true) {
                r.talpha = true
                col.copyInto(r.rgba)
                rv = rv or 1
            }
        }
        if (rv and 1 != 0) r.tin = (1.0f / 3.0f) * (r.rgba[0] + r.rgba[1] + r.rgba[2])
        else { r.rgba[0] = r.tin; r.rgba[1] = r.tin; r.rgba[2] = r.tin }
    }
}

internal object BlendGeneratedImage {
    private fun hsvByte(h: Float, s: Float, v: Float, px: IntArray, o: Int) {
        val rgb = BlendColor.hsvToRgb(h, s, v)
        px[o] = (rgb[0] * 255.0f).toInt() and 0xFF
        px[o + 1] = (rgb[1] * 255.0f).toInt() and 0xFF
        px[o + 2] = (rgb[2] * 255.0f).toInt() and 0xFF
        px[o + 3] = 255
    }

    private fun unitByte(f: Float) = if (f <= 0f) 0 else if (f > 1.0f - 0.5f / 255.0f) 255 else (255.0f * f + 0.5f).toInt()

    fun blank(w: Int, h: Int, color: FloatArray): IntArray {
        val px = IntArray(w * h * 4)
        val c = IntArray(4) { unitByte(color[it]) }
        for (i in 0 until w * h) for (k in 0 until 4) px[i * 4 + k] = c[k]
        return px
    }

    fun blankFloat(w: Int, h: Int, color: FloatArray, data: Boolean): FloatArray {
        val c = if (data) color.copyOf() else floatArrayOf(BlendColorSpace.srgbToLinear(color[0]), BlendColorSpace.srgbToLinear(color[1]), BlendColorSpace.srgbToLinear(color[2]), color[3])
        return FloatArray(w * h * 4) { c[it % 4] }
    }

    fun uvGridFloat(w: Int, h: Int): FloatArray {
        val px = FloatArray(w * h * 4)
        val cs = 32
        val half = cs / 2
        val darkC = BlendColorSpace.srgbToLinear(0.25f)
        val brightC = BlendColorSpace.srgbToLinear(0.58f)
        for (y in 0 until h) {
            var dark = (-1.0f).pow(floor((y / cs).toFloat())).toInt()
            for (x in 0 until w) {
                if (x % cs == 0) dark = -dark
                val o = (y * w + x) * 4
                val v = if (dark > 0) darkC else brightC
                px[o] = v; px[o + 1] = v; px[o + 2] = v; px[o + 3] = 1.0f
            }
        }
        for (y in 0 until h) {
            val hoffs = 0.125f * floor((y / cs).toFloat())
            for (x in 0 until w) {
                val hh = 0.125f * floor((x / cs).toFloat())
                val tx = abs((x % cs) - half)
                val ty = abs((y % cs) - half)
                if (tx < 4 && ty < 4 && (tx < 1 || ty < 1)) {
                    val rgb = BlendColor.hsvToRgb(abs(hh - hoffs) % 1.0f, 0.9f, 0.9f)
                    val o = (y * w + x) * 4
                    for (k in 0 until 3) px[o + k] = BlendColorSpace.srgbToLinear(rgb[k])
                    px[o + 3] = 1.0f
                }
            }
        }
        return px
    }

    fun uvGrid(w: Int, h: Int): IntArray {
        val px = IntArray(w * h * 4)
        val cs = 32
        val half = cs / 2
        for (y in 0 until h) {
            var dark = (-1.0f).pow(floor((y / cs).toFloat())).toInt()
            for (x in 0 until w) {
                if (x % cs == 0) dark = -dark
                val o = (y * w + x) * 4
                val v = if (dark > 0) 64 else 150
                px[o] = v; px[o + 1] = v; px[o + 2] = v; px[o + 3] = 255
            }
        }
        for (y in 0 until h) {
            val hoffs = 0.125f * floor((y / cs).toFloat())
            for (x in 0 until w) {
                val hh = 0.125f * floor((x / cs).toFloat())
                val tx = abs((x % cs) - half)
                val ty = abs((y % cs) - half)
                if (tx < 4 && ty < 4 && (tx < 1 || ty < 1)) hsvByte(abs(hh - hoffs) % 1.0f, 0.9f, 0.9f, px, (y * w + x) * 4)
            }
        }
        return px
    }

    fun colorGrid(w: Int, h: Int): IntArray {
        val px = IntArray(w * h * 4)
        var step = 1
        while (step < w / 8) step = step shl 1
        step = maxOf(step, 8)
        for (y in 0 until h) {
            val v = (0.1 + (y * (0.4 / h))).toFloat()
            for (x in 0 until w) hsvByte((((x / step).toDouble() * 1.0 / w * step)).toFloat(), 1.0f, v, px, (y * w + x) * 4)
        }
        fun blend(real: Int, add: Float): Int {
            val a = ((add * 255.0f).toInt()) and 0xFF
            return if (real + a <= 255) (real + a) and 0xFF else 255
        }
        fun tint(size: Int, b: Float) {
            val bh = b * 0.5f
            for (y in 0 until h) for (x in 0 until w) {
                val o = (y * w + x) * 4
                val even = ((y / size) % 2 == 1 && (x / size) % 2 == 1) || ((y / size) % 2 == 0 && (x / size) % 2 == 0)
                val add = if (even) b else bh
                for (k in 0 until 3) px[o + k] = blend(px[o + k], add)
                px[o + 3] = 255
            }
        }
        tint(1, 0.03f); tint(4, 0.05f); tint(32, 0.07f); tint(128, 0.15f)
        for (y in 0 until h) for (x in 0 until w) {
            if (y % 32 == 0 || x % 32 == 0 || x == 0) {
                val o = (y * w + x) * 4
                for (k in 0 until 3) px[o + k] = blend(px[o + k], 0.25f)
                px[o + 3] = 255
            }
        }
        return px
    }
}

internal object BlendColorSpace {
    fun srgbToLinear(c: Float): Float = if (c < 0.04045f) (if (c < 0.0f) 0.0f else c * (1.0f / 12.92f)) else ((c + 0.055f) * (1.0f / 1.055f)).pow(2.4f)

    fun isData(name: String) = name in setOf("Non-Color", "Raw", "Generic Data", "data")

    fun isSceneLinear(name: String) = name in setOf("Linear", "Linear Rec.709", "Linear CIE-XYZ D65", "scene_linear", "Linear Rec.709 (sRGB)")

    fun toSceneLinear(px: FloatArray, name: String, predivide: Boolean) {
        if (isData(name) || isSceneLinear(name)) return
        for (i in 0 until px.size / 4) {
            val o = i * 4
            val a = px[o + 3]
            val div = predivide && a != 1.0f && a != 0.0f
            for (k in 0 until 3) {
                var v = px[o + k]
                if (div) v /= a
                v = srgbToLinear(v)
                if (div) v *= a
                px[o + k] = v
            }
        }
    }
}
