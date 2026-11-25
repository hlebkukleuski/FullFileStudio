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

package me.hlebkukleuski.fullfilestudio.feature.gpl.blend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlendAnimationTest {

    private fun key(x: Float, y: Float, ipo: Int, lx: Float = x, ly: Float = y, rx: Float = x, ry: Float = y) =
        BlendAnimator.Kf(x, y, lx, ly, rx, ry, ipo, 0, 1.70158f, 0.8f, 4.1f)

    private fun curve(keys: List<BlendAnimator.Kf>, extend: Int = 0, mods: List<BlendAnimator.Mod> = emptyList()) =
        BlendAnimator.Curve("location", 0, keys, extend, mods, false)

    private fun ramp(extend: Int = 0, mods: List<BlendAnimator.Mod> = emptyList()) = curve(listOf(key(0f, 0f, 1), key(10f, 10f, 1)), extend, mods)

    private fun bounds(p: FloatArray): FloatArray {
        val r = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in p.indices) {
            r[i % 3] = minOf(r[i % 3], p[i])
            r[3 + i % 3] = maxOf(r[3 + i % 3], p[i])
        }
        return r
    }

    private fun maxDiff(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d = maxOf(d, kotlin.math.abs(a[i] - b[i]))
        return d
    }

    private fun load(name: String): AnimSink {
        val bytes = javaClass.classLoader!!.getResourceAsStream("blend/$name.blend")!!.readBytes()
        val sink = AnimSink()
        BlendLoader(BlendFile(BlendFile.decompress(bytes)), sink).load()
        return sink
    }

    @Test
    fun linearConstantAndExtrapolation() {
        assertEquals(5f, BlendAnimator.evaluate(ramp(), 5f), 1e-5f)
        assertEquals(10f, BlendAnimator.evaluate(ramp(), 15f), 1e-5f)
        assertEquals(0f, BlendAnimator.evaluate(ramp(), -4f), 1e-5f)
        assertEquals(15f, BlendAnimator.evaluate(ramp(extend = 1), 15f), 1e-5f)
        assertEquals(-4f, BlendAnimator.evaluate(ramp(extend = 1), -4f), 1e-5f)
        val step = curve(listOf(key(0f, 0f, 0), key(10f, 10f, 0)))
        assertEquals(0f, BlendAnimator.evaluate(step, 9.9f), 1e-5f)
        assertEquals(10f, BlendAnimator.evaluate(step, 10f), 1e-5f)
    }

    @Test
    fun bezierMatchesBlenderHandleCorrection() {
        val smooth = curve(listOf(
            key(0f, 0f, 2, lx = -10f / 3, rx = 10f / 3),
            key(10f, 10f, 2, lx = 20f / 3, rx = 40f / 3)
        ))
        assertEquals(5f, BlendAnimator.evaluate(smooth, 5f), 1e-4f)
        assertEquals(1.5625f, BlendAnimator.evaluate(smooth, 2.5f), 1e-4f)
        val long = curve(listOf(
            key(0f, 0f, 2, rx = 7f, ry = 0f),
            key(10f, 10f, 2, lx = 3f, ly = 10f)
        ))
        assertEquals(0.36121f, BlendAnimator.evaluate(long, 2f), 1e-4f)
    }

    @Test
    fun cycleAndSteppedModifiers() {
        fun cycles(mode: Int) = listOf(BlendAnimator.Mod(4, mode, mode, 0, 0, FloatArray(0)))
        assertEquals(5f, BlendAnimator.evaluate(ramp(mods = cycles(1)), 15f), 1e-4f)
        assertEquals(15f, BlendAnimator.evaluate(ramp(mods = cycles(2)), 15f), 1e-4f)
        assertEquals(8f, BlendAnimator.evaluate(ramp(mods = cycles(3)), 12f), 1e-4f)
        assertEquals(7f, BlendAnimator.evaluate(ramp(mods = cycles(1)), -3f), 1e-4f)
        assertEquals(-3f, BlendAnimator.evaluate(ramp(mods = cycles(2)), -3f), 1e-4f)
        val limited = listOf(BlendAnimator.Mod(4, 0, 1, 0, 1, FloatArray(0)))
        assertEquals(5f, BlendAnimator.evaluate(ramp(mods = limited), 15f), 1e-4f)
        assertEquals(10f, BlendAnimator.evaluate(ramp(mods = limited), 25f), 1e-4f)
        val stepped = listOf(BlendAnimator.Mod(9, 0, 0, 0, 0, floatArrayOf(2f, 0f, 0f, 0f)))
        assertEquals(2f, BlendAnimator.evaluate(ramp(mods = stepped), 3.5f), 1e-4f)
        val generator = listOf(BlendAnimator.Mod(1, 0, 0, 1, 0, floatArrayOf(1f, 2f)))
        assertEquals(7f, BlendAnimator.evaluate(curve(emptyList(), mods = generator), 3f), 1e-4f)
    }

    @Test
    fun driverExpressions() {
        val env = mapOf("var" to 3.0, "a" to -2.0, "b" to 5.0, "frame" to 12.0)
        assertEquals(7.0, DriverExpression("var * 2 + 1", env).evaluate()!!, 1e-9)
        assertEquals(512.0, DriverExpression("2 ** 3 ** 2", env).evaluate()!!, 1e-9)
        assertEquals(-4.0, DriverExpression("-2 ** 2", env).evaluate()!!, 1e-9)
        assertEquals(3.0, DriverExpression("7 // 2", env).evaluate()!!, 1e-9)
        assertEquals(1.0, DriverExpression("0 < var < 4", env).evaluate()!!, 1e-9)
        assertEquals(0.0, DriverExpression("0 < var < 2", env).evaluate()!!, 1e-9)
        assertEquals(0.0, DriverExpression("a < b < var", env).evaluate()!!, 1e-9)
        assertEquals(1.0, DriverExpression("a < var <= 3 == var", env).evaluate()!!, 1e-9)
        assertEquals(1.0, DriverExpression("7 % 3", env).evaluate()!!, 1e-9)
        assertEquals(1.0, DriverExpression("sin(pi / 2)", env).evaluate()!!, 1e-9)
        assertEquals(-1.0, DriverExpression("max(a, b) if a > 0 else -1", env).evaluate()!!, 1e-9)
        assertEquals(5.0, DriverExpression("max(a, b) if not a > 0 and b >= 5 else 0", env).evaluate()!!, 1e-9)
        assertEquals(1.2, DriverExpression("frame / 10", env).evaluate()!!, 1e-9)
        assertEquals(0.5, DriverExpression("math.radians(90) / pi", env).evaluate()!!, 1e-9)
        assertNull(DriverExpression("bpy.data.objects['x'].location.x", env).evaluate())
        assertNull(DriverExpression("var +", env).evaluate())
    }

    @Test
    fun skinnedCylinderMatchesBlenderExport() {
        val sink = load("anim_03_skinned_cylinder")
        val a = sink.animation()
        assertNotNull(a)
        assertEquals(316, sink.polys)
        assertEquals(316, sink.triBone.count { it == -2 })
        assertEquals(60f, a!!.fps, 1e-4f)
        assertEquals(1, a.clipCount)
        assertEquals("Scene", a.clipName(0))
        assertEquals(2f, a.duration(0), 1e-4f)
        val static = sink.verts.toFloatArray()
        assertTrue(maxDiff(sink.pose(0f), static) < 1e-4f)
        val mid = bounds(sink.pose(0.5f))
        assertEquals(-1.5373f, mid[1], 2e-3f)
        assertEquals(4.2401f, mid[5], 2e-3f)
        val rest = bounds(sink.pose(1f))
        val expected = floatArrayOf(-1f, -1f, 0f, 1f, 1f, 4f)
        for (k in 0 until 6) assertEquals(expected[k], rest[k], 2e-3f)
    }

    @Test
    fun shapeKeysFollowActionsAndBoneDrivers() {
        val sink = load("anim_28_shapekeys_anim")
        val a = sink.animation()!!
        assertEquals(2, a.morphCount)
        assertEquals(12, sink.triBone.count { it == -2 })
        val w = a.morphWeights!!
        assertEquals(0f, w[0], 1e-4f)
        assertEquals(1f, w[1], 1e-4f)
        assertEquals(0.58299f, w[5 * 2], 1e-3f)
        assertEquals(0.83402f, w[5 * 2 + 1], 1e-3f)
        assertEquals(1f, w[9 * 2], 1e-4f)
        assertEquals(0f, w[9 * 2 + 1], 1e-4f)
        val first = bounds(sink.pose(0f))
        assertEquals(7f, first[4], 1e-3f)
        assertEquals(4f, first[5], 1e-3f)
        val last = bounds(sink.pose(a.duration(0)))
        assertEquals(6f, last[4], 1e-3f)
        assertEquals(2f, last[5], 1e-3f)
    }

    @Test
    fun nlaStripsReproduceSavedFrame() {
        val sink = load("anim_07_nla-anim")
        val a = sink.animation()!!
        assertEquals(36, sink.triBone.count { it >= 0 })
        assertTrue(maxDiff(sink.pose(59f / a.fps), sink.verts.toFloatArray()) < 1e-4f)
        val mid = bounds(sink.pose(30f / a.fps))
        assertEquals(7f, mid[3], 1e-3f)
        assertEquals(6.9927f, mid[4], 1e-3f)
        assertEquals(3.9927f, mid[5], 1e-3f)
    }

    @Test
    fun boneParentedObjectReproducesSavedFrame() {
        val sink = load("anim_00_parented_to_bone_and_animated")
        val a = sink.animation()!!
        assertEquals(10, a.frameCount)
        assertTrue(maxDiff(sink.pose(9f / a.fps), sink.verts.toFloatArray()) < 1e-4f)
        val start = bounds(sink.pose(0f))
        assertEquals(13.9661f, start[3], 1e-3f)
        assertEquals(2.4293f, start[5], 1e-3f)
    }

    @Test
    fun copyRotationConstraintReproducesSavedPose() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("blend/anim_29_armature_use_current_pose.blend")!!.readBytes()
        val f = BlendFile(BlendFile.decompress(bytes))
        val an = BlendAnimator(f)
        val fr = an.Frame(BlendAnimator.Clip("probe", an.currentFrame, an.currentFrame, emptyMap()), an.currentFrame, true)
        var checked = 0
        for (b in f.blocks) {
            if (b.code != "OB") continue
            val o = f.view("Object", b.offset) ?: continue
            if (o.int("type") != 25) continue
            for (ch in an.poseChannels(o)) {
                val stored = ch.floats("pose_mat", 16) ?: continue
                val computed = fr.bonePose(o, ch.str("name"))!!
                for (k in 0 until 16) assertEquals(stored[k], computed[k], 1e-4f)
                checked++
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun trackingConstraintsAimTheRequestedAxis() {
        val target = RigMath.withLoc(RigMath.I, floatArrayOf(5f, 0f, 0f))
        val damped = ConstraintMath.dampTrack(RigMath.I, target, 1)
        val y = RigMath.norm(RigMath.col(damped, 1))
        assertEquals(1f, y[0], 1e-5f)
        val tracked = ConstraintMath.trackTo(RigMath.I, RigMath.withLoc(RigMath.I, floatArrayOf(0f, 0f, 5f)), 1, 2, false)
        val ty = RigMath.col(tracked, 1)
        assertEquals(1f, ty[2], 1e-5f)
        assertEquals(1f, RigMath.det3(tracked), 1e-5f)
        val locked = ConstraintMath.lockTrack(RigMath.I, RigMath.withLoc(RigMath.I, floatArrayOf(3f, 3f, 7f)), 1, 2)
        val ly = RigMath.col(locked, 1)
        assertEquals(0f, ly[2], 1e-5f)
        assertEquals(kotlin.math.sqrt(0.5f), ly[0], 1e-5f)
        assertEquals(1f, RigMath.col(locked, 2)[2], 1e-5f)
    }

    @Test
    fun matrixInterpolationIsPolarAndSlerped() {
        val a = RigMath.I
        val b = RigMath.compose(floatArrayOf(2f, 0f, 0f), RigMath.axisAngle(floatArrayOf(0f, 0f, 1f), (Math.PI / 2).toFloat()), floatArrayOf(3f, 3f, 3f))
        val m = RigMath.interp(a, b, 0.5f)
        assertEquals(1f, m[12], 1e-5f)
        val s = RigMath.size(m)
        assertEquals(2f, s[0], 1e-4f)
        val angle = kotlin.math.atan2(m[1], m[0])
        assertEquals((Math.PI / 4).toFloat(), angle, 1e-4f)
    }

    @Test
    fun ikSolverReachesGoalAndHonoursPole() {
        fun bone(y: Float) = RigMath.withLoc(RigMath.I, floatArrayOf(0f, y, 0f))
        val segs = listOf(
            IkSegment("a", -1, bone(0f), null, 1f, booleanArrayOf(true, true, true), floatArrayOf(1f, 1f, 1f), null, RigMath.I, 0f),
            IkSegment("b", 0, RigMath.mul(bone(1f), RigMath.axisAngle(floatArrayOf(1f, 0f, 0f), 0.3f)), null, 1f, booleanArrayOf(true, true, true), floatArrayOf(1f, 1f, 1f), null, RigMath.I, 0f)
        )
        val goal = floatArrayOf(1f, 1f, 0f)
        val solver = IkSolver(segs, listOf(IkTask(1, goal, null, 1f, 1f)))
        solver.poleAlign(0, 1, goal, floatArrayOf(0f, 0f, 5f), 0f)
        solver.solve(500)
        val qa = solver.basisChange(0)
        val qb = solver.basisChange(1)
        val pa = RigMath.mul(segs[0].fkPose, qa)
        val pb = RigMath.mul(pa, RigMath.mul(bone(1f), RigMath.axisAngle(floatArrayOf(1f, 0f, 0f), 0.3f)), qb)
        val tip = RigMath.add(RigMath.loc(pb), RigMath.col(pb, 1))
        for (k in 0 until 3) assertEquals(goal[k], tip[k], 2e-3f)
        assertEquals(1f, RigMath.norm(RigMath.col(pa, 0))[2], 1e-3f)
    }

    @Test
    fun straightBBoneJointsAreEvenlySpacedAndCurveBends() {
        val p = BBoneParams().apply { segments = 4; length = 2f }
        val joints = BBoneMath.compute(p)
        assertEquals(5, joints.size)
        for (i in 0..4) {
            assertEquals(0.5f * i, joints[i][13], 1e-3f)
            assertEquals(1f, joints[i][5], 1e-4f)
        }
        val bent = BBoneMath.compute(BBoneParams().apply { segments = 4; length = 2f; curveInX = 0.5f; curveOutX = 0.5f; ease1 = 1f; ease2 = 1f })
        assertTrue(bent[2][12] > 0.1f)
        assertEquals(0f, bent[0][12], 1e-5f)
        assertEquals(2f, bent[4][13], 1e-4f)
    }

    @Test
    fun pathSamplesByArcLength() {
        val pts = listOf(PathPoint(floatArrayOf(0f, 0f, 0f), 0f, 1f), PathPoint(floatArrayOf(1f, 0f, 0f), 0f, 1f), PathPoint(floatArrayOf(4f, 0f, 0f), 0f, 3f))
        for (p in pts) p.dir = floatArrayOf(1f, 0f, 0f)
        val path = BlendPath(pts, false)
        assertEquals(4f, path.length, 1e-5f)
        val mid = path.where(0.5f)!!
        assertEquals(2f, mid.vec[0], 1e-5f)
        assertEquals(1f + 2f * (1f / 3f), mid.radius, 1e-4f)
        val past = path.where(1.25f)!!
        assertEquals(5f, past.vec[0], 1e-4f)
        val q = BlendPath.vecToQuat(floatArrayOf(0f, 0f, 1f), 5, 1)
        val m = RigMath.quatMat(q)
        assertEquals(1f, m[10], 1e-5f)
    }
}
