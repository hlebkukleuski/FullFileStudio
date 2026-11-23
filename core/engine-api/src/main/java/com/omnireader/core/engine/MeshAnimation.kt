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

package com.omnireader.core.engine

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.sqrt

class MeshAnimation(
    val fps: Float,
    val frameCount: Int,
    val boneCount: Int,
    val matrices: FloatArray,
    val triBone: IntArray,
    val skinOffset: IntArray?,
    val skinJoints: IntArray?,
    val skinWeights: FloatArray?,
    val skinBind: FloatArray?,
    val skinBindNormals: FloatArray?,
    val morphCount: Int,
    val morphPoly: IntArray?,
    val morphTarget: IntArray?,
    val morphDelta: FloatArray?,
    val morphBase: FloatArray?,
    val morphWeights: FloatArray?,
    val morphNormalDelta: FloatArray? = null,
    val clipNames: List<String> = emptyList(),
    val clipStarts: IntArray? = null,
    val clipFrames: IntArray? = null,
    val skinInfluences: Int = 4,
    val skinDq: IntArray? = null
) {
    val clipCount: Int get() = if (clipStarts != null && clipFrames != null && clipNames.isNotEmpty()) minOf(clipNames.size, clipStarts.size, clipFrames.size) else 1

    fun clipName(clip: Int): String = clipNames.getOrNull(clip) ?: ""

    private fun clipStart(clip: Int): Int = if (clipCount > 1 || clipStarts != null) (clipStarts?.getOrNull(clip.coerceIn(0, clipCount - 1)) ?: 0).coerceIn(0, maxOf(0, frameCount - 1)) else 0

    private fun clipLength(clip: Int): Int {
        val start = clipStart(clip)
        val len = clipFrames?.getOrNull(clip.coerceIn(0, clipCount - 1)) ?: frameCount
        return len.coerceIn(1, maxOf(1, frameCount - start))
    }

    fun duration(clip: Int): Float = if (fps > 0f) (clipLength(clip) - 1) / fps else 0f

    val duration: Float get() = duration(0)

    fun encodedSize(): Int {
        var s = 4 * 8
        s += matrices.size * 4 + triBone.size * 4
        s += 4 + (skinOffset?.size ?: 0) * 4
        s += 4 + (skinJoints?.size ?: 0) * 4
        s += 4 + (skinWeights?.size ?: 0) * 4
        s += 4 + (skinBind?.size ?: 0) * 4
        s += 4 + (skinBindNormals?.size ?: 0) * 4
        s += 4 + (morphPoly?.size ?: 0) * 4
        s += 4 + (morphTarget?.size ?: 0) * 4
        s += 4 + (morphDelta?.size ?: 0) * 4
        s += 4 + (morphBase?.size ?: 0) * 4
        s += 4 + (morphWeights?.size ?: 0) * 4
        s += 4 + (morphNormalDelta?.size ?: 0) * 4
        s += 4
        if (clipStarts != null && clipFrames != null) for (i in 0 until clipCount) s += 12 + clipNames[i].toByteArray(Charsets.UTF_8).size
        if (skinDq != null) s += 4 + skinDq.size * 4
        return s
    }

    fun encode(out: ByteBuffer) {
        out.putFloat(fps)
        out.putInt(frameCount)
        out.putInt(boneCount)
        out.putInt(morphCount)
        out.putInt(matrices.size)
        out.putInt(triBone.size)
        out.putInt(skinInfluences)
        out.putInt(if (skinDq != null) 1 else 0)
        putFloats(out, matrices)
        putInts(out, triBone)
        optInts(out, skinOffset)
        optInts(out, skinJoints)
        optFloats(out, skinWeights)
        optFloats(out, skinBind)
        optFloats(out, skinBindNormals)
        optInts(out, morphPoly)
        optInts(out, morphTarget)
        optFloats(out, morphDelta)
        optFloats(out, morphBase)
        optFloats(out, morphWeights)
        optFloats(out, morphNormalDelta)
        if (clipStarts != null && clipFrames != null && clipNames.isNotEmpty()) {
            out.putInt(clipCount)
            for (i in 0 until clipCount) {
                val name = clipNames[i].toByteArray(Charsets.UTF_8)
                out.putInt(clipStarts[i])
                out.putInt(clipFrames[i])
                out.putInt(name.size)
                out.put(name)
            }
        } else out.putInt(0)
        if (skinDq != null) optInts(out, skinDq)
    }

    class Pose(val vertices: FloatArray, val normals: FloatArray?)

    private var poseVerts: FloatArray? = null
    private var poseNormals: FloatArray? = null
    private var curMats = FloatArray(0)
    private var curWeights = FloatArray(0)
    private var morphedBase: FloatArray? = null
    private var morphedBind: FloatArray? = null
    private var morphedNormals: FloatArray? = null
    private var morphedBindNormals: FloatArray? = null
    private var dqCache = FloatArray(0)
    private var dqStamp = IntArray(0)
    private var poseStamp = 0
    private val dqAcc = FloatArray(8)

    private fun boneDualQuat(b: Int): Int {
        val at = b * 8
        if (dqStamp[b] == poseStamp) return at
        val m = b * 12
        val c = curMats
        fun len(o: Int) = sqrt(c[o] * c[o] + c[o + 1] * c[o + 1] + c[o + 2] * c[o + 2]).takeIf { it > 0f } ?: 1f
        val l0 = len(m)
        val l1 = len(m + 3)
        val l2 = len(m + 6)
        val m00 = c[m] / l0; val m10 = c[m + 1] / l0; val m20 = c[m + 2] / l0
        val m01 = c[m + 3] / l1; val m11 = c[m + 4] / l1; val m21 = c[m + 5] / l1
        val m02 = c[m + 6] / l2; val m12 = c[m + 7] / l2; val m22 = c[m + 8] / l2
        var w: Float
        var x: Float
        var y: Float
        var z: Float
        val tr = m00 + m11 + m22
        if (tr > 0f) {
            val s = sqrt(tr + 1f) * 2f
            w = 0.25f * s; x = (m21 - m12) / s; y = (m02 - m20) / s; z = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            w = (m21 - m12) / s; x = 0.25f * s; y = (m01 + m10) / s; z = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            w = (m02 - m20) / s; x = (m01 + m10) / s; y = 0.25f * s; z = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = 0.25f * s
        }
        val ql = sqrt(w * w + x * x + y * y + z * z).takeIf { it > 0f } ?: 1f
        w /= ql; x /= ql; y /= ql; z /= ql
        val tx = c[m + 9]
        val ty = c[m + 10]
        val tz = c[m + 11]
        val d = dqCache
        d[at] = w; d[at + 1] = x; d[at + 2] = y; d[at + 3] = z
        d[at + 4] = 0.5f * (-tx * x - ty * y - tz * z)
        d[at + 5] = 0.5f * (tx * w + ty * z - tz * y)
        d[at + 6] = 0.5f * (-tx * z + ty * w + tz * x)
        d[at + 7] = 0.5f * (tx * y - ty * x + tz * w)
        dqStamp[b] = poseStamp
        return at
    }

    private fun dualQuatCorner(so: Int, k: Int, pre: Int, bindSrc: FloatArray, bindN: FloatArray?, verts: FloatArray, normals: FloatArray?, src: FloatArray, o: Int) {
        val joints = skinJoints ?: return
        val weights = skinWeights ?: return
        val inf = skinInfluences
        val acc = dqAcc
        acc.fill(0f)
        var sw = 0f
        val pm = pre * 12
        val c = curMats
        val bx0 = bindSrc[so * 9 + k * 3]
        val by0 = bindSrc[so * 9 + k * 3 + 1]
        val bz0 = bindSrc[so * 9 + k * 3 + 2]
        val px = c[pm] * bx0 + c[pm + 3] * by0 + c[pm + 6] * bz0 + c[pm + 9]
        val py = c[pm + 1] * bx0 + c[pm + 4] * by0 + c[pm + 7] * bz0 + c[pm + 10]
        val pz = c[pm + 2] * bx0 + c[pm + 5] * by0 + c[pm + 8] * bz0 + c[pm + 11]
        for (j in 0 until inf) {
            val w = weights.getOrElse(so * 3 * inf + k * inf + j) { 0f }
            if (w == 0f) continue
            val bone = joints[so * 3 * inf + k * inf + j]
            if (bone !in 0 until boneCount - 1) continue
            val q = boneDualQuat(bone)
            val d = dqCache
            val sm = (bone + 1) * 12
            val d0 = c[sm] * px + c[sm + 3] * py + c[sm + 6] * pz + c[sm + 9] - px
            val d1 = c[sm + 1] * px + c[sm + 4] * py + c[sm + 7] * pz + c[sm + 10] - py
            val d2 = c[sm + 2] * px + c[sm + 5] * py + c[sm + 8] * pz + c[sm + 11] - pz
            val m0 = d[q]; val m1 = d[q + 1]; val m2 = d[q + 2]; val m3 = d[q + 3]
            val t0 = d[q + 4] - 0.5f * (m1 * d0 + m2 * d1 + m3 * d2)
            val t1 = d[q + 5] + 0.5f * (m0 * d0 + m2 * d2 - m3 * d1)
            val t2 = d[q + 6] + 0.5f * (m0 * d1 + m3 * d0 - m1 * d2)
            val t3 = d[q + 7] + 0.5f * (m0 * d2 + m1 * d1 - m2 * d0)
            val dot = acc[0] * m0 + acc[1] * m1 + acc[2] * m2 + acc[3] * m3
            val sw2 = if (dot < 0f) -w else w
            acc[0] += sw2 * m0; acc[1] += sw2 * m1; acc[2] += sw2 * m2; acc[3] += sw2 * m3
            acc[4] += sw2 * t0; acc[5] += sw2 * t1; acc[6] += sw2 * t2; acc[7] += sw2 * t3
            sw += w
        }
        val vo = o + k * 3
        if (sw <= 0f) {
            verts[vo] = src[vo]; verts[vo + 1] = src[vo + 1]; verts[vo + 2] = src[vo + 2]
            if (normals != null) { normals[vo] = 0f; normals[vo + 1] = 0f; normals[vo + 2] = 0f }
            return
        }
        val ql = sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2] + acc[3] * acc[3]).takeIf { it > 0f } ?: 1f
        val w = acc[0] / ql; val x = acc[1] / ql; val y = acc[2] / ql; val z = acc[3] / ql
        val dw = acc[4] / ql; val dx = acc[5] / ql; val dy = acc[6] / ql; val dz = acc[7] / ql
        val tx = 2f * (-dw * x + dx * w - dy * z + dz * y)
        val ty = 2f * (-dw * y + dx * z + dy * w - dz * x)
        val tz = 2f * (-dw * z - dx * y + dy * x + dz * w)
        val r00 = 1f - 2f * (y * y + z * z); val r01 = 2f * (x * y - w * z); val r02 = 2f * (x * z + w * y)
        val r10 = 2f * (x * y + w * z); val r11 = 1f - 2f * (x * x + z * z); val r12 = 2f * (y * z - w * x)
        val r20 = 2f * (x * z - w * y); val r21 = 2f * (y * z + w * x); val r22 = 1f - 2f * (x * x + y * y)
        val qm = (pre + 1) * 12
        fun apply(px: Float, py: Float, pz: Float, point: Boolean, out: FloatArray, at: Int) {
            val t = if (point) 1f else 0f
            val ax = c[pm] * px + c[pm + 3] * py + c[pm + 6] * pz + c[pm + 9] * t
            val ay = c[pm + 1] * px + c[pm + 4] * py + c[pm + 7] * pz + c[pm + 10] * t
            val az = c[pm + 2] * px + c[pm + 5] * py + c[pm + 8] * pz + c[pm + 11] * t
            val cx = r00 * ax + r01 * ay + r02 * az + tx * t
            val cy = r10 * ax + r11 * ay + r12 * az + ty * t
            val cz = r20 * ax + r21 * ay + r22 * az + tz * t
            out[at] = c[qm] * cx + c[qm + 3] * cy + c[qm + 6] * cz + c[qm + 9] * t
            out[at + 1] = c[qm + 1] * cx + c[qm + 4] * cy + c[qm + 7] * cz + c[qm + 10] * t
            out[at + 2] = c[qm + 2] * cx + c[qm + 5] * cy + c[qm + 8] * cz + c[qm + 11] * t
        }
        apply(bindSrc[so * 9 + k * 3], bindSrc[so * 9 + k * 3 + 1], bindSrc[so * 9 + k * 3 + 2], true, verts, vo)
        if (normals != null) {
            if (bindN != null) {
                apply(bindN[so * 9 + k * 3], bindN[so * 9 + k * 3 + 1], bindN[so * 9 + k * 3 + 2], false, normals, vo)
                val l = sqrt(normals[vo] * normals[vo] + normals[vo + 1] * normals[vo + 1] + normals[vo + 2] * normals[vo + 2])
                if (l > 0f) for (i in 0 until 3) normals[vo + i] /= l
            } else {
                normals[vo] = 0f; normals[vo + 1] = 0f; normals[vo + 2] = 0f
            }
        }
    }

    @Synchronized
    fun pose(time: Float, base: FloatArray, count: Int, baseNormals: FloatArray?, clip: Int = 0): Pose {
        val polys = count / 9
        val verts = poseVerts?.takeIf { it.size >= count } ?: FloatArray(count).also { poseVerts = it }
        val normals = if (baseNormals != null) {
            poseNormals?.takeIf { it.size >= polys * 9 } ?: FloatArray(polys * 9).also { poseNormals = it }
        } else null
        val start = clipStart(clip)
        val len = clipLength(clip)
        val f = if (len <= 1) 0f else (time * fps).coerceIn(0f, (len - 1).toFloat())
        val k0 = start + floor(f).toInt().coerceIn(0, len - 1)
        val k1 = minOf(k0 + 1, start + len - 1)
        val a = (f - floor(f)).coerceIn(0f, 1f)
        if (curMats.size != boneCount * 12) curMats = FloatArray(boneCount * 12)
        val stride = boneCount * 12
        poseStamp++
        if (dqStamp.size != boneCount) {
            dqStamp = IntArray(boneCount)
            dqCache = FloatArray(boneCount * 8)
        }
        for (i in 0 until stride) {
            val v0 = matrices.getOrElse(k0 * stride + i) { 0f }
            val v1 = matrices.getOrElse(k1 * stride + i) { v0 }
            curMats[i] = v0 + (v1 - v0) * a
        }
        var src = base
        var bindSrc = skinBind
        var nSrc = baseNormals
        var bindNSrc = skinBindNormals
        if (morphCount > 0 && morphPoly != null && morphTarget != null && morphDelta != null) {
            if (curWeights.size != morphCount) curWeights = FloatArray(morphCount)
            val mw = morphWeights
            for (t in 0 until morphCount) {
                val w0 = mw?.getOrElse(k0 * morphCount + t) { 0f } ?: 0f
                val w1 = mw?.getOrElse(k1 * morphCount + t) { w0 } ?: w0
                curWeights[t] = w0 + (w1 - w0) * a - (morphBase?.getOrElse(t) { 0f } ?: 0f)
            }
            val mb = morphedBase?.takeIf { it.size >= count } ?: FloatArray(count).also { morphedBase = it }
            System.arraycopy(base, 0, mb, 0, count)
            val sb = skinBind
            val mbind = if (sb != null) (morphedBind?.takeIf { it.size >= sb.size } ?: FloatArray(sb.size).also { morphedBind = it }).also {
                System.arraycopy(sb, 0, it, 0, sb.size)
            } else null
            val nd = morphNormalDelta
            val mn = if (nd != null && baseNormals != null) (morphedNormals?.takeIf { it.size >= polys * 9 } ?: FloatArray(polys * 9).also { morphedNormals = it }).also {
                System.arraycopy(baseNormals, 0, it, 0, minOf(baseNormals.size, polys * 9))
            } else null
            val sbn = skinBindNormals
            val mbn = if (nd != null && sbn != null) (morphedBindNormals?.takeIf { it.size >= sbn.size } ?: FloatArray(sbn.size).also { morphedBindNormals = it }).also {
                System.arraycopy(sbn, 0, it, 0, sbn.size)
            } else null
            for (e in morphPoly.indices) {
                val p = morphPoly[e]
                val w = curWeights.getOrElse(morphTarget[e]) { 0f }
                if (w == 0f || p !in 0 until polys) continue
                val so = skinOffset?.getOrElse(p) { -1 } ?: -1
                if (triBone.getOrElse(p) { -1 } == -2 && so >= 0 && mbind != null) {
                    for (k in 0 until 9) mbind[so * 9 + k] += w * morphDelta[e * 9 + k]
                    if (mbn != null && nd != null) for (k in 0 until 9) mbn[so * 9 + k] += w * nd[e * 9 + k]
                } else {
                    for (k in 0 until 9) mb[p * 9 + k] += w * morphDelta[e * 9 + k]
                    if (mn != null && nd != null) for (k in 0 until 9) mn[p * 9 + k] += w * nd[e * 9 + k]
                }
            }
            src = mb
            bindSrc = mbind ?: skinBind
            if (mn != null) nSrc = mn
            if (mbn != null) bindNSrc = mbn
        }
        for (p in 0 until polys) {
            val o = p * 9
            val b = triBone.getOrElse(p) { -1 }
            when {
                b >= 0 && b < boneCount -> {
                    val m = b * 12
                    for (k in 0 until 3) {
                        val x = src[o + k * 3]
                        val y = src[o + k * 3 + 1]
                        val z = src[o + k * 3 + 2]
                        verts[o + k * 3] = curMats[m] * x + curMats[m + 3] * y + curMats[m + 6] * z + curMats[m + 9]
                        verts[o + k * 3 + 1] = curMats[m + 1] * x + curMats[m + 4] * y + curMats[m + 7] * z + curMats[m + 10]
                        verts[o + k * 3 + 2] = curMats[m + 2] * x + curMats[m + 5] * y + curMats[m + 8] * z + curMats[m + 11]
                        if (normals != null && nSrc != null) rotateNormal(curMats, m, nSrc, o + k * 3, normals)
                    }
                }
                b == -2 && skinOffset != null && skinJoints != null && skinWeights != null && bindSrc != null -> {
                    val so = skinOffset.getOrElse(p) { -1 }
                    if (so < 0) {
                        copyTri(src, verts, nSrc, normals, o)
                        continue
                    }
                    val dqPre = skinDq?.getOrElse(so) { -1 } ?: -1
                    if (dqPre >= 0 && dqPre + 1 < boneCount) {
                        for (k in 0 until 3) dualQuatCorner(so, k, dqPre, bindSrc, bindNSrc, verts, normals, src, o)
                        continue
                    }
                    val inf = skinInfluences
                    for (k in 0 until 3) {
                        val bx = bindSrc[so * 9 + k * 3]
                        val by = bindSrc[so * 9 + k * 3 + 1]
                        val bz = bindSrc[so * 9 + k * 3 + 2]
                        var sx = 0f
                        var sy = 0f
                        var sz = 0f
                        var nx = 0f
                        var ny = 0f
                        var nz = 0f
                        var sw = 0f
                        val bn = bindNSrc
                        for (j in 0 until inf) {
                            val w = skinWeights.getOrElse(so * 3 * inf + k * inf + j) { 0f }
                            if (w == 0f) continue
                            val bone = skinJoints[so * 3 * inf + k * inf + j]
                            if (bone !in 0 until boneCount) continue
                            val m = bone * 12
                            sx += w * (curMats[m] * bx + curMats[m + 3] * by + curMats[m + 6] * bz + curMats[m + 9])
                            sy += w * (curMats[m + 1] * bx + curMats[m + 4] * by + curMats[m + 7] * bz + curMats[m + 10])
                            sz += w * (curMats[m + 2] * bx + curMats[m + 5] * by + curMats[m + 8] * bz + curMats[m + 11])
                            if (bn != null) {
                                val ux = bn[so * 9 + k * 3]
                                val uy = bn[so * 9 + k * 3 + 1]
                                val uz = bn[so * 9 + k * 3 + 2]
                                nx += w * (curMats[m] * ux + curMats[m + 3] * uy + curMats[m + 6] * uz)
                                ny += w * (curMats[m + 1] * ux + curMats[m + 4] * uy + curMats[m + 7] * uz)
                                nz += w * (curMats[m + 2] * ux + curMats[m + 5] * uy + curMats[m + 8] * uz)
                            }
                            sw += w
                        }
                        if (sw > 0f) {
                            verts[o + k * 3] = sx / sw
                            verts[o + k * 3 + 1] = sy / sw
                            verts[o + k * 3 + 2] = sz / sw
                        } else {
                            verts[o + k * 3] = src[o + k * 3]
                            verts[o + k * 3 + 1] = src[o + k * 3 + 1]
                            verts[o + k * 3 + 2] = src[o + k * 3 + 2]
                        }
                        if (normals != null) {
                            val l = sqrt(nx * nx + ny * ny + nz * nz)
                            if (bn != null && l > 0f) {
                                normals[o + k * 3] = nx / l
                                normals[o + k * 3 + 1] = ny / l
                                normals[o + k * 3 + 2] = nz / l
                            } else {
                                normals[o + k * 3] = 0f
                                normals[o + k * 3 + 1] = 0f
                                normals[o + k * 3 + 2] = 0f
                            }
                        }
                    }
                }
                else -> copyTri(src, verts, nSrc, normals, o)
            }
        }
        return Pose(verts, normals)
    }

    fun bounds(base: FloatArray, count: Int): FloatArray {
        val out = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (clip in 0 until clipCount) {
            val d = duration(clip)
            val samples = if (d > 0f) minOf(48, clipLength(clip)) else 1
            for (s in 0 until samples) {
                val t = if (samples > 1) d * s / (samples - 1) else 0f
                val v = pose(t, base, count, null, clip).vertices
                var i = 0
                while (i + 2 < count) {
                    val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
                    if (x.isFinite() && y.isFinite() && z.isFinite()) {
                        if (x < out[0]) out[0] = x
                        if (x > out[1]) out[1] = x
                        if (y < out[2]) out[2] = y
                        if (y > out[3]) out[3] = y
                        if (z < out[4]) out[4] = z
                        if (z > out[5]) out[5] = z
                    }
                    i += 3
                }
            }
        }
        return out
    }

    private fun copyTri(src: FloatArray, verts: FloatArray, baseNormals: FloatArray?, normals: FloatArray?, o: Int) {
        System.arraycopy(src, o, verts, o, 9)
        if (normals != null && baseNormals != null && o + 9 <= baseNormals.size) {
            for (k in 0 until 3) {
                val x = baseNormals[o + k * 3]
                val y = baseNormals[o + k * 3 + 1]
                val z = baseNormals[o + k * 3 + 2]
                val l = sqrt(x * x + y * y + z * z)
                if (l > 0f) {
                    normals[o + k * 3] = x / l
                    normals[o + k * 3 + 1] = y / l
                    normals[o + k * 3 + 2] = z / l
                } else {
                    normals[o + k * 3] = 0f
                    normals[o + k * 3 + 1] = 0f
                    normals[o + k * 3 + 2] = 0f
                }
            }
        }
    }

    private fun rotateNormal(m: FloatArray, at: Int, n: FloatArray, o: Int, out: FloatArray) {
        if (o + 2 >= n.size) return
        val x = n[o]
        val y = n[o + 1]
        val z = n[o + 2]
        val rx = m[at] * x + m[at + 3] * y + m[at + 6] * z
        val ry = m[at + 1] * x + m[at + 4] * y + m[at + 7] * z
        val rz = m[at + 2] * x + m[at + 5] * y + m[at + 8] * z
        val l = sqrt(rx * rx + ry * ry + rz * rz)
        if (l > 0f) {
            out[o] = rx / l
            out[o + 1] = ry / l
            out[o + 2] = rz / l
        } else {
            out[o] = 0f
            out[o + 1] = 0f
            out[o + 2] = 0f
        }
    }

    companion object {
        private const val MAX_ELEMENTS = 64 * 1024 * 1024

        private fun putFloats(out: ByteBuffer, a: FloatArray) {
            out.asFloatBuffer().put(a)
            out.position(out.position() + a.size * 4)
        }

        private fun putInts(out: ByteBuffer, a: IntArray) {
            out.asIntBuffer().put(a)
            out.position(out.position() + a.size * 4)
        }

        private fun optFloats(out: ByteBuffer, a: FloatArray?) {
            out.putInt(a?.size ?: -1)
            if (a != null) putFloats(out, a)
        }

        private fun optInts(out: ByteBuffer, a: IntArray?) {
            out.putInt(a?.size ?: -1)
            if (a != null) putInts(out, a)
        }

        private fun floats(b: ByteBuffer, n: Int): FloatArray {
            require(n in 0..MAX_ELEMENTS && n.toLong() * 4 <= b.remaining()) { "Corrupted mesh animation" }
            return FloatArray(n).also {
                b.asFloatBuffer().get(it)
                b.position(b.position() + n * 4)
            }
        }

        private fun ints(b: ByteBuffer, n: Int): IntArray {
            require(n in 0..MAX_ELEMENTS && n.toLong() * 4 <= b.remaining()) { "Corrupted mesh animation" }
            return IntArray(n).also {
                b.asIntBuffer().get(it)
                b.position(b.position() + n * 4)
            }
        }

        private fun optFloats(b: ByteBuffer): FloatArray? = b.int.let { if (it < 0) null else floats(b, it) }
        private fun optInts(b: ByteBuffer): IntArray? = b.int.let { if (it < 0) null else ints(b, it) }

        fun decode(b: ByteBuffer, polygonCount: Int): MeshAnimation {
            val fps = b.float
            val frames = b.int
            val bones = b.int
            val morphs = b.int
            val matCount = b.int
            val triCount = b.int
            val influences = b.int.let { if (it <= 0) 4 else it }
            val flags = b.int
            require(influences in 1..64) { "Corrupted mesh animation" }
            require(fps.isFinite() && fps > 0f && frames in 1..1_000_000 && bones in 0..1_000_000 && morphs in 0..1_000_000) { "Corrupted mesh animation" }
            require(matCount.toLong() == frames.toLong() * bones * 12 && triCount == polygonCount) { "Corrupted mesh animation" }
            val matrices = floats(b, matCount)
            val triBone = ints(b, triCount)
            val skinOffset = optInts(b)
            val skinJoints = optInts(b)
            val skinWeights = optFloats(b)
            val skinBind = optFloats(b)
            val skinBindNormals = optFloats(b)
            val morphPoly = optInts(b)
            val morphTarget = optInts(b)
            val morphDelta = optFloats(b)
            val morphBase = optFloats(b)
            val morphWeights = optFloats(b)
            val morphNormalDelta = optFloats(b)
            val clips = b.int
            require(clips in 0..10_000) { "Corrupted mesh animation clips" }
            val names = ArrayList<String>()
            val starts = IntArray(clips)
            val lens = IntArray(clips)
            for (i in 0 until clips) {
                starts[i] = b.int
                lens[i] = b.int
                val n = b.int
                require(n in 0..4096 && n <= b.remaining()) { "Corrupted mesh animation clip name" }
                val arr = ByteArray(n)
                b.get(arr)
                names.add(String(arr, Charsets.UTF_8))
            }
            val skinDq = if (flags and 1 != 0) optInts(b) else null
            return MeshAnimation(
                fps, frames, bones, matrices, triBone,
                skinOffset, skinJoints, skinWeights, skinBind, skinBindNormals,
                morphs, morphPoly, morphTarget, morphDelta, morphBase, morphWeights, morphNormalDelta,
                names, if (clips > 0) starts else null, if (clips > 0) lens else null, influences, skinDq
            )
        }
    }
}
