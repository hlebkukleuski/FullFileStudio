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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import me.hlebkukleuski.fullfilestudio.feature.cadvector.GltfParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlendModifierTest {

    private val startups = listOf("startup_279.blend", "startup_293.blend", "startup_36.blend")

    private fun raw(name: String) = javaClass.classLoader!!.getResourceAsStream("blend/$name")!!.readBytes()

    private fun deform(name: String, mods: List<Triple<String, Int, (BlendPatcher.Fill) -> Unit>>): Pair<FloatArray, FloatArray> {
        val p = BlendPatcher(raw(name))
        p.addModifiers("Cube", mods)
        val f = BlendFile(p.build())
        val blend = BlendLoader(f, AnimSink())
        val ob = f.blocks.first { it.code == "OB" && f.view("Object", it.offset)!!.sub("id")!!.str("name") == "OBCube" }.let { f.view("Object", it.offset)!! }
        val stack = blend.stackOf(ob)
        assertEquals(name, mods.size, stack.size)
        return blend.deformedLocal(ob, emptyList())!! to blend.deformedLocal(ob, stack)!!
    }

    private fun check(name: String, src: FloatArray, got: FloatArray, expected: (FloatArray) -> FloatArray) {
        assertEquals(name, 24, got.size)
        for (v in 0 until 8) {
            val e = expected(floatArrayOf(src[v * 3], src[v * 3 + 1], src[v * 3 + 2]))
            for (k in 0 until 3) assertEquals("$name v$v[$k]", e[k], got[v * 3 + k], 1e-5f)
        }
    }

    @Test
    fun castSphere() = startups.forEach { n ->
        val (src, got) = deform(n, listOf(Triple("CastModifierData", 17) { m ->
            m.float("fac", 0.5f); m.float("size", 2f); m.int("flag", 14); m.int("type", 0)
        }))
        check(n, src, got) { v -> val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); FloatArray(3) { 0.5f * v[it] / l * 2f + 0.5f * v[it] } }
    }

    @Test
    fun castCuboidWithRadiusLeavesCubeOnItsBounds() = startups.forEach { n ->
        val (src, got) = deform(n, listOf(Triple("CastModifierData", 17) { m ->
            m.float("fac", 1f); m.float("radius", 2f); m.int("flag", 14 or 32); m.int("type", 2)
        }))
        check(n, src, got) { v -> FloatArray(3) { v[it] * 2f } }
    }

    @Test
    fun simpleDeformTwist() = startups.forEach { n ->
        val (src, got) = deform(n, listOf(Triple("SimpleDeformModifierData", 28) { m ->
            m.int("mode", 1); m.float("factor", (PI / 2).toFloat()); m.floats("limit", floatArrayOf(0f, 1f))
            if (m.has("deform_axis")) m.int("deform_axis", 2)
        }))
        check(n, src, got) { v ->
            val th = v[2] * (PI / 2).toFloat() / 2f
            floatArrayOf(v[0] * cos(th) - v[1] * sin(th), v[0] * sin(th) + v[1] * cos(th), v[2])
        }
    }

    @Test
    fun smoothUsesEdgeMidpoints() = startups.forEach { n ->
        val (src, got) = deform(n, listOf(Triple("SmoothModifierData", 16) { m ->
            m.float("fac", 0.5f); m.int("flag", 14); m.int("repeat", 2)
        }))
        val once = 0.5f + 0.5f * (2f / 3f)
        check(n, src, got) { v -> FloatArray(3) { v[it] * once * once } }
    }

    @Test
    fun displaceAlongNormals() = startups.forEach { n ->
        val (src, got) = deform(n, listOf(Triple("DisplaceModifierData", 14) { m ->
            m.float("strength", 2f); m.float("midlevel", 0.25f); m.int("direction", 3)
        }))
        check(n, src, got) { v -> val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); FloatArray(3) { v[it] + 1.5f * v[it] / l } }
    }

    @Test
    fun displaceLocalAxis() = startups.forEach { n ->
        val (src, got) = deform(n, listOf(Triple("DisplaceModifierData", 14) { m ->
            m.float("strength", 1f); m.float("midlevel", 0f); m.int("direction", 2); if (m.has("space")) m.int("space", 0)
        }))
        check(n, src, got) { v -> floatArrayOf(v[0], v[1], v[2] + 1f) }
    }

    @Test
    fun waveFromCustomStart() = startups.forEach { n ->
        val (src, got) = deform(n, listOf(Triple("WaveModifierData", 7) { m ->
            m.int("flag", 2 or 4); m.float("startx", 1f); m.float("starty", 1f); m.float("height", 0.5f); m.float("width", 1.5f)
            m.float("narrow", 1.5f); m.float("speed", 0f); m.float("damp", 10f)
        }))
        val minfac = exp(-(1.5f * 1.5f * 1.5f * 1.5f))
        check(n, src, got) { v ->
            val d = sqrt((v[0] - 1f) * (v[0] - 1f) + (v[1] - 1f) * (v[1] - 1f))
            val a = if (d < 1.5f) (1f / exp((d * 1.5f) * (d * 1.5f)) - minfac) * 0.5f else 0f
            floatArrayOf(v[0], v[1], v[2] + a)
        }
    }

    @Test
    fun stackAppliesInOrderAndRespectsDisabledModifiers() = startups.forEach { n ->
        val (src, got) = deform(n, listOf(
            Triple("DisplaceModifierData", 14) { m -> m.float("strength", 1f); m.float("midlevel", 0f); m.int("direction", 0) },
            Triple("CastModifierData", 17) { m -> m.float("fac", 1f); m.float("size", 3f); m.int("flag", 14) }
        ))
        check(n, src, got) { v ->
            val p = floatArrayOf(v[0] + 1f, v[1], v[2])
            val l = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            FloatArray(3) { p[it] / l * 3f }
        }
        val p = BlendPatcher(raw(n))
        p.addModifiers("Cube", listOf(Triple("DisplaceModifierData", 14) { m -> m.int("modifier.mode", 2); m.float("strength", 1f); m.int("direction", 0) }))
        val f = BlendFile(p.build())
        val ob = f.blocks.first { it.code == "OB" && f.view("Object", it.offset)!!.sub("id")!!.str("name") == "OBCube" }.let { f.view("Object", it.offset)!! }
        assertTrue(n, BlendLoader(f, AnimSink()).stackOf(ob).isEmpty())
    }

    private fun cube(name: String = "startup_36.blend"): Triple<BlendFile, BlendLoader, BlendFile.View> {
        val f = BlendFile(BlendFile.decompress(raw(name)))
        val blend = BlendLoader(f, AnimSink())
        return Triple(f, blend, obj(f, "Cube"))
    }

    private fun obj(f: BlendFile, name: String) = f.blocks.first { it.code == "OB" && f.view("Object", it.offset)!!.sub("id")!!.str("name") == "OB$name" }.let { f.view("Object", it.offset)!! }

    private fun cubeTopology(): Pair<FloatArray, MeshTopology> {
        val (f, blend, ob) = cube()
        val me = f.view("Mesh", f.offsetOf(ob.ptr("data"))!!)!!
        return blend.deformedLocal(ob, emptyList())!! to blend.topology(me)!!
    }

    @Test
    fun laplacianSmoothPreservesCubeVolume() = startups.forEach { n ->
        val (src, got) = deform(n, listOf(Triple("LaplacianSmoothModifierData", 43) { m ->
            m.float("lambda", 0.5f); m.float("lambda_border", 0.5f); m.int("flag", 2 or 4 or 8 or 16 or 32); m.int("repeat", 2)
        }))
        check(n, src, got) { v -> v }
        val (_, shrunk) = deform(n, listOf(Triple("LaplacianSmoothModifierData", 43) { m ->
            m.float("lambda", 0.5f); m.float("lambda_border", 0.5f); m.int("flag", 2 or 4 or 8 or 32); m.int("repeat", 1)
        }))
        val c = kotlin.math.abs(shrunk[0])
        assertTrue(n, c < 0.999f && c > 0.1f)
        for (x in shrunk) assertEquals(n, c, kotlin.math.abs(x), 1e-5f)
    }

    private fun hasStruct(n: String, st: String) = BlendFile(BlendFile.decompress(raw(n))).struct(st) != null

    @Test
    fun correctiveSmoothRestoresUndeformedMesh() = startups.filter { hasStruct(it, "CorrectiveSmoothModifierData") }.also { assertTrue(it.size >= 2) }.forEach { n ->
        for (type in 0..1) {
            val (src, got) = deform(n, listOf(Triple("CorrectiveSmoothModifierData", 51) { m ->
                m.float("lambda", 0.5f); m.float("scale", 1f); m.int("repeat", 5); m.int("smooth_type", type)
            }))
            check(n, src, got) { v -> v }
        }
        val (_, onlySmooth) = deform(n, listOf(Triple("CorrectiveSmoothModifierData", 51) { m ->
            m.float("lambda", 0.5f); m.float("scale", 1f); m.int("repeat", 1); m.int("flag", 2)
        }))
        for (x in onlySmooth) assertEquals(n, 2f / 3f, kotlin.math.abs(x), 1e-5f)
    }

    @Test
    fun correctiveSmoothFollowsRigidMotion() {
        val (rest, topo) = cubeTopology()
        val m = GltfParser.trs(floatArrayOf(0.5f, -2f, 1f), floatArrayOf(0.1f, 0.4f, -0.2f, 0.8888f).let { q -> val l = sqrt(q.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(4) { q[it] / l } }, null)
        val moved = FloatArray(rest.size).also { o -> for (v in 0 until 8) RigMath.point(m, rest.copyOfRange(v * 3, v * 3 + 3)).copyInto(o, v * 3) }
        val co = moved.copyOf()
        BlendModifiers.correctiveSmooth(co, rest, topo, null, 0.5f, 1f, 4, 0, 1)
        for (i in co.indices) assertEquals(moved[i], co[i], 1e-5f)
    }

    private fun lampName(n: String): String {
        val f = BlendFile(BlendFile.decompress(raw(n)))
        return f.blocks.first { it.code == "OB" && f.view("Object", it.offset)!!.int("type") == 10 }.let { f.view("Object", it.offset)!!.sub("id")!!.str("name").drop(2) }
    }

    @Test
    fun warpMovesVerticesFromSourceToTargetObject() = startups.forEach { n ->
        val p = BlendPatcher(raw(n))
        p.addModifiers("Cube", listOf(Triple("WarpModifierData", 35) { m ->
            m.ptr("object_from", p.objectAddress(lampName(n))); m.ptr("object_to", p.objectAddress("Camera"))
            m.float("strength", 1f); m.float("falloff_radius", 1f); m.int("falloff_type", 0)
        }))
        val f = BlendFile(p.build())
        val blend = BlendLoader(f, AnimSink())
        val ob = obj(f, "Cube")
        val an = BlendAnimator(f)
        val w = an.staticFrame
        val obInv = BlendAnimator.invert(w.world(ob))
        val from = GltfParser.mul(obInv, w.world(obj(f, lampName(n))))
        val to = GltfParser.mul(obInv, w.world(obj(f, "Camera")))
        val xf = GltfParser.mul(to, BlendAnimator.invert(from))
        val src = blend.deformedLocal(ob, emptyList())!!
        val got = blend.deformedLocal(ob, blend.stackOf(ob))!!
        check(n, src, got) { v -> RigMath.point(xf, v) }
    }

    @Test
    fun curveMapMatchesBlenderDefaults() {
        val lin = BlendCurveMap(floatArrayOf(0f, 1f), floatArrayOf(0f, 1f), intArrayOf(0, 0), 1, floatArrayOf(0f, 1f, 0f, 1f))
        for (x in listOf(0f, 0.1f, 0.25f, 0.5f, 0.9f, 1f)) assertEquals(x, lin.evaluate(x), 1e-4f)
        assertEquals(1f, lin.evaluate(3f), 1e-6f)
        assertEquals(0f, lin.evaluate(-1f), 1e-6f)
        val vec = BlendCurveMap(floatArrayOf(0f, 0.5f, 1f), floatArrayOf(0f, 1f, 0f), intArrayOf(2, 2, 2), 0, floatArrayOf(0f, 1f, 0f, 1f))
        assertEquals(0.5f, vec.evaluate(0.25f), 2e-3f)
        assertEquals(1f, vec.evaluate(0.5f), 2e-3f)
        assertEquals(0.5f, vec.evaluate(0.75f), 2e-3f)
        val smooth = BlendCurveMap(floatArrayOf(0f, 0.5f, 1f), floatArrayOf(0f, 1f, 0f), intArrayOf(0, 0, 0), 0, floatArrayOf(0f, 1f, 0f, 1f))
        assertTrue(smooth.evaluate(0.25f) > 0.5f)
        assertEquals(smooth.evaluate(0.25f), smooth.evaluate(0.75f), 1e-3f)
    }

    private fun cubeTarget(): ShrinkTree {
        val (co, topo) = cubeTopology()
        return ShrinkTree(co, topo, null)
    }

    @Test
    fun shrinkwrapNearestSurfaceVertexAndProjection() {
        val tree = cubeTarget()
        val pts = floatArrayOf(3f, 0.2f, -0.4f, 0.3f, 0.2f, 0.1f, 2f, 2f, 2f, -0.5f, 0.25f, 5f)
        val near = pts.copyOf()
        Shrinkwrap.deform(near, null, null, 0, 1, 0, 0f, 0f, 0, tree, GltfParser.IDENTITY, null, null)
        val exp = floatArrayOf(1f, 0.2f, -0.4f, 1f, 0.2f, 0.1f, 1f, 1f, 1f, -0.5f, 0.25f, 1f)
        for (i in exp.indices) assertEquals("nearest $i", exp[i], near[i], 1e-5f)
        val outside = pts.copyOf()
        Shrinkwrap.deform(outside, null, null, 0, 1, 2, 0f, 0f, 0, tree, GltfParser.IDENTITY, null, null)
        for (k in 0 until 3) assertEquals(pts[k], outside[k], 1e-6f)
        assertEquals(1f, outside[3], 1e-5f)
        val vert = pts.copyOf()
        Shrinkwrap.deform(vert, null, null, 2, 1, 0, 0f, 0f, 0, tree, GltfParser.IDENTITY, null, null)
        for (v in 0 until 4) for (k in 0 until 3) assertEquals(1f, kotlin.math.abs(vert[v * 3 + k]), 1e-5f)
        val proj = pts.copyOf()
        Shrinkwrap.deform(proj, null, null, 1, 1 or 2, 0, 0f, 0f, 4, tree, GltfParser.IDENTITY, null, null)
        assertEquals(3f, proj[0], 1e-6f)
        assertEquals(1f, proj[11], 1e-5f)
        assertEquals(-0.5f, proj[9], 1e-6f)
        val target = floatArrayOf(3f, 0f, 0f)
        Shrinkwrap.deform(target, null, null, 3, 1, 0, 0.25f, 0f, 0, tree, GltfParser.IDENTITY, null, null)
        assertEquals(1.25f, target[0], 1e-4f)
        assertEquals(0f, target[1], 1e-4f)
        val moved = GltfParser.trs(floatArrayOf(10f, 0f, 0f), null, null)
        val local = floatArrayOf(13f, 0.2f, -0.4f)
        Shrinkwrap.deform(local, null, null, 0, 1, 0, 0f, 0f, 0, tree, BlendAnimator.invert(moved), null, null)
        assertEquals(11f, local[0], 1e-5f)
    }

    @Test
    fun shrinkwrapConstraintSnapsOwnerToTargetSurface() = startups.forEach { n ->
        for (type in listOf(0, 2)) {
            val p = BlendPatcher(raw(n))
            p.addConstraint("Camera", null, 20, "bShrinkwrapConstraint", { c ->
                c.ptr("target", p.objectAddress("Cube")); c.int("shrinkType", type)
                if (c.has("shrinkMode")) c.int("shrinkMode", 0)
            })
            val f = BlendFile(p.build())
            val an = BlendAnimator(f)
            val cam = obj(f, "Camera")
            val stored = an.staticFrame.storedWorld(cam)
            val blend = BlendLoader(f, AnimSink())
            val w = blend.forcedWorld(cam)!!
            val clamped = FloatArray(3) { stored[12 + it].coerceIn(-1f, 1f) }
            for (k in 0 until 3) assertEquals("$n type$type", if (type == 2) Math.signum(stored[12 + k]) else clamped[k], w[12 + k], 1e-4f)
            for (k in 0 until 12) if (k % 4 != 3) assertEquals(stored[k], w[k], 1e-4f)
        }
    }

    @Test
    fun surfaceDeformFollowsBoundTriangle() {
        val target = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val bind = SurfaceBind(intArrayOf(0), intArrayOf(0, 1), listOf(intArrayOf(0, 1, 2)), listOf(floatArrayOf(0.2f, 0.3f, 0.5f)), intArrayOf(0), floatArrayOf(0.4f), floatArrayOf(1f), GltfParser.IDENTITY)
        val co = floatArrayOf(9f, 9f, 9f)
        BlendModifiers.surfaceDeform(co, null, bind, target, 1f)
        assertEquals(0.3f, co[0], 1e-6f); assertEquals(0.5f, co[1], 1e-6f); assertEquals(0.4f, co[2], 1e-6f)
        val moved = floatArrayOf(0f, 0f, 2f, 0f, 1f, 2f, -1f, 0f, 2f)
        val co2 = floatArrayOf(0f, 0f, 0f)
        BlendModifiers.surfaceDeform(co2, null, bind, moved, 1f)
        assertEquals(-0.5f, co2[0], 1e-6f); assertEquals(0.3f, co2[1], 1e-6f); assertEquals(2.4f, co2[2], 1e-6f)
        val half = floatArrayOf(0f, 0f, 0f)
        BlendModifiers.surfaceDeform(half, floatArrayOf(0.5f), bind, target, 1f)
        assertEquals(0.15f, half[0], 1e-6f)
    }

    @Test
    fun laplacianDeformKeepsRigidMotion() {
        val (rest, topo) = cubeTopology()
        val anchors = (0 until 8).filter { rest[it * 3 + 2] < 0f }.toIntArray()
        val co = rest.copyOf()
        for (a in anchors) { co[a * 3] += 2f; co[a * 3 + 1] -= 1f }
        assertTrue(BlendModifiers.laplacianDeform(co, rest, topo, anchors, 2))
        for (v in 0 until 8) {
            assertEquals(rest[v * 3] + 2f, co[v * 3], 1e-4f)
            assertEquals(rest[v * 3 + 1] - 1f, co[v * 3 + 1], 1e-4f)
            assertEquals(rest[v * 3 + 2], co[v * 3 + 2], 1e-4f)
        }
    }

    @Test
    fun splineTailIntersectsSphereOnPath() {
        val pts = (0..10).map { PathPoint(floatArrayOf(it * 0.5f, 0f, 0f), 0f, 1f + it * 0.1f) }
        val path = BlendPath(pts, false)
        val t = SplineMath.tailOnSpline(path, floatArrayOf(0f, 0f, 0f), 1.3f, 0, 0.2f)
        assertEquals(1.3f, t.tail[0], 1e-5f)
        assertEquals(1.3f / 5f, t.pos, 1e-5f)
        assertEquals(1f + 0.26f, t.radius, 1e-5f)
        assertEquals(intArrayOf(2, 0, 1).toList(), SplineMath.axisSort(floatArrayOf(0.5f, 0.9f, -1f)).toList())
    }

    @Test
    fun curvedBBoneMappingMatchesStraightForStraightBone() {
        val rest = (0..4).map { i -> GltfParser.IDENTITY.copyOf().also { it[13] = i * 0.5f } }
        val map = BBoneCurvedMap(GltfParser.IDENTITY, rest)
        for (y in listOf(-0.2f, 0f, 0.3f, 0.75f, 1.1f, 1.9f, 2.4f)) assertEquals(y.coerceIn(0f, 2f) / 2f, map.headTail(floatArrayOf(0.1f, y, -0.3f)), 1e-5f)
        val arc = (0..4).map { i ->
            val a = i * 0.25f
            GltfParser.trs(floatArrayOf(1f - kotlin.math.cos(a), kotlin.math.sin(a), 0f), floatArrayOf(0f, 0f, -kotlin.math.sin(a / 2), kotlin.math.cos(a / 2)), null)
        }
        val cm = BBoneCurvedMap(GltfParser.IDENTITY, arc)
        for (i in 0..4) assertEquals(i / 4f, cm.headTail(floatArrayOf(arc[i][12], arc[i][13], 0f)), 1e-4f)
    }

    @Test
    fun viewLayerVisibilityHidesExcludedCollections() {
        fun load(bytes: ByteArray) = BlendLoader(BlendFile(bytes), AnimSink()).load()
        val base = BlendFile.decompress(raw("startup_36.blend"))
        assertEquals(1, load(base.copyOf()))
        fun patched(mod: (BlendFile, ByteArray) -> Unit): ByteArray {
            val b = base.copyOf()
            mod(BlendFile(b), b)
            return b
        }
        fun orInt(f: BlendFile, b: ByteArray, v: BlendFile.View, field: String, bits: Int) {
            val fd = v.struct.fields[field]!!
            val at = v.at + fd.offset
            if (fd.size == 2) { val x = f.bb.getShort(at).toInt() or bits; java.nio.ByteBuffer.wrap(b).order(f.bb.order()).putShort(at, x.toShort()) }
            else { val x = f.bb.getInt(at) or bits; java.nio.ByteBuffer.wrap(b).order(f.bb.order()).putInt(at, x) }
        }
        fun layer(f: BlendFile) = f.view("ViewLayer", f.offsetOf(f.activeScene()!!.sub("view_layers")!!.ptr("first"))!!)!!
        fun childLc(f: BlendFile): BlendFile.View {
            val top = f.view("LayerCollection", f.offsetOf(layer(f).sub("layer_collections")!!.ptr("first"))!!)!!
            return f.view("LayerCollection", f.offsetOf(top.sub("layer_collections")!!.ptr("first"))!!)!!
        }
        assertEquals(0, load(patched { f, b -> orInt(f, b, childLc(f), "flag", 1 shl 4) }))
        assertEquals(0, load(patched { f, b -> orInt(f, b, childLc(f), "flag", 1 shl 7) }))
        assertEquals(0, load(patched { f, b -> orInt(f, b, f.view("Collection", f.offsetOf(childLc(f).ptr("collection"))!!)!!, "flag", 1) }))
        assertEquals(0, load(patched { f, b ->
            val cube = obj(f, "Cube")
            var p = layer(f).sub("object_bases")!!.ptr("first")
            while (p != 0L) {
                val bs = f.view("Base", f.offsetOf(p)!!)!!
                if (f.offsetOf(bs.ptr("object")) == cube.at) orInt(f, b, bs, "flag", 1 shl 8)
                p = bs.ptr("next")
            }
        }))
        assertEquals(1, load(patched { f, b -> orInt(f, b, childLc(f), "flag", 1 shl 3) }))
    }


    @Test
    fun polyfillTriangulatesConcavePolygonsExactly() {
        fun area(p: FloatArray, a: Int, b: Int, c: Int) = ((p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) - (p[c * 2] - p[a * 2]) * (p[b * 2 + 1] - p[a * 2 + 1])) / 2f
        val star = FloatArray(20).also { p -> for (i in 0 until 10) { val r = if (i % 2 == 0) 1f else 0.4f; val a = Math.PI * 2 * i / 10; p[i * 2] = (r * kotlin.math.cos(a)).toFloat(); p[i * 2 + 1] = (r * kotlin.math.sin(a)).toFloat() } }
        val ell = floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 1f, 1f, 1f, 2f, 0.5f, 2f, 0f, 2f)
        val collinear = floatArrayOf(0f, 0f, 1f, 0f, 2f, 0f, 2f, 2f, 1f, 1f, 0f, 2f)
        for (ccw in listOf(star, ell, collinear)) {
            val n = ccw.size / 2
            val poly = FloatArray(ccw.size).also { r -> for (i in 0 until n) { r[i * 2] = ccw[(n - 1 - i) * 2]; r[i * 2 + 1] = ccw[(n - 1 - i) * 2 + 1] } }
            val t = BlendPolyfill.fill(poly, n)
            assertEquals(3 * (n - 2), t.size)
            var total = 0f
            for (k in t.indices step 3) {
                val a = area(poly, t[k], t[k + 1], t[k + 2])
                assertTrue(a <= 0f)
                total += a
            }
            var shoelace = 0f
            for (i in 0 until n) { val j = (i + 1) % n; shoelace += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1] }
            assertEquals(shoelace / 2f, total, 1e-5f)
        }
        for (rev in listOf(false, true)) {
            val co = FloatArray(30).also { c -> for (i in 0 until 10) { val r = if (i % 2 == 0) 1f else 0.4f; val a = Math.PI * 2 * i / 10 * (if (rev) -1 else 1); c[i * 3] = (r * kotlin.math.cos(a)).toFloat() + 3f; c[i * 3 + 1] = 0.5f; c[i * 3 + 2] = (r * kotlin.math.sin(a)).toFloat() } }
            val tri = BlendPolyfill.triangulate(co, IntArray(10) { it })
            assertEquals(24, tri.size)
            var sum = 0f
            for (k in tri.indices step 3) {
                val p0 = co.copyOfRange(tri[k] * 3, tri[k] * 3 + 3); val p1 = co.copyOfRange(tri[k + 1] * 3, tri[k + 1] * 3 + 3); val p2 = co.copyOfRange(tri[k + 2] * 3, tri[k + 2] * 3 + 3)
                sum += RigMath.len(RigMath.cross(RigMath.sub(p1, p0), RigMath.sub(p2, p0))) / 2f
            }
            var area = 0f
            for (i in 0 until 10) { val j = (i + 1) % 10; area += co[i * 3] * co[j * 3 + 2] - co[j * 3] * co[i * 3 + 2] }
            assertEquals(kotlin.math.abs(area) / 2f, sum, 1e-4f)
        }
    }

}
