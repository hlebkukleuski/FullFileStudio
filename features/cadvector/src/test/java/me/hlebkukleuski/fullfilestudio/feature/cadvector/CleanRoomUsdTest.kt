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

package me.hlebkukleuski.fullfilestudio.feature.cadvector

import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CleanRoomUsdTest {

    private class Sink : GltfOutput {
        var tris = 0
        var texTris = 0
        val colors = HashMap<Int, Int>()
        val textures = ArrayList<String>()
        val mn = FloatArray(3) { Float.MAX_VALUE }
        val mx = FloatArray(3) { -Float.MAX_VALUE }
        val uvs = ArrayList<Float>()
        val points = ArrayList<FloatArray>()
        override val truncated = false
        override fun triangle(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int) {
            tris++
            for (k in 0 until 3) points.add(floatArrayOf(p[k * 3], p[k * 3 + 1], p[k * 3 + 2]))
            if (texture >= 0) {
                texTris++
                uv?.forEach { uvs.add(it) }
            }
            colors[color] = (colors[color] ?: 0) + 1
            for (k in 0 until 9) {
                mn[k % 3] = minOf(mn[k % 3], p[k])
                mx[k % 3] = maxOf(mx[k % 3], p[k])
            }
        }
        override fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int) {}
        override fun texture(key: String, bytes: ByteArray?, opaque: Boolean): Int {
            textures.add(key)
            return textures.size - 1
        }
        override fun resource(uri: String): ByteArray? = null
    }

    private fun usdz(name: String): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(javaClass.getResourceAsStream("/usd/$name")!!).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun load(files: Map<String, ByteArray>, root: String): Sink {
        val sink = Sink()
        val stage = UsdStage({ files[it] }, root)
        assertTrue(stage.rootLayer != null)
        assertTrue(CleanRoomUsd(stage, sink).load())
        return sink
    }

    @Test
    fun crateTriangle() {
        val files = usdz("AnimatedTriangle.usdz")
        val sink = load(files, files.keys.first())
        assertEquals(1, sink.tris)
        assertEquals(0f, sink.mn[0], 1e-6f)
        assertEquals(1f, sink.mx[0], 1e-6f)
        assertEquals(1f, sink.mx[1], 1e-6f)
    }

    @Test
    fun crateBoxWithMaterials() {
        val files = usdz("BoxAnimated.usdz")
        val sink = load(files, files.keys.first())
        assertEquals(254, sink.tris)
        assertEquals(192, sink.colors[0xFF95C1E7.toInt()])
        assertEquals(62, sink.colors[0xFFE7ADE7.toInt()])
        for (k in 0 until 3) {
            assertEquals(-0.5f, sink.mn[k], 1e-4f)
            assertEquals(0.5f, sink.mx[k], 1e-4f)
        }
    }

    private val asset = """
#usda 1.0
(
    defaultPrim = "Asset"
)

def Xform "Asset" (
    variants = {
        string look = "red"
    }
    prepend variantSets = "look"
)
{
    def Mesh "Quad" (
        prepend apiSchemas = ["MaterialBindingAPI"]
    )
    {
        point3f[] points = [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0)]
        int[] faceVertexCounts = [3, 3]
        int[] faceVertexIndices = [0, 1, 2, 0, 2, 3]
        texCoord2f[] primvars:st = [(0, 0), (1, 0), (1, 1), (0, 1)] (
            interpolation = "vertex"
        )
        uniform token subdivisionScheme = "none"
        def GeomSubset "second" (
            prepend apiSchemas = ["MaterialBindingAPI"]
        )
        {
            uniform token elementType = "face"
            uniform token familyName = "materialBind"
            int[] indices = [1]
            rel material:binding = </Asset/Looks/Tex>
        }
    }

    def Scope "Looks"
    {
        def Material "Red"
        {
            token outputs:surface.connect = </Asset/Looks/Red/S.outputs:surface>
            def Shader "S"
            {
                uniform token info:id = "UsdPreviewSurface"
                color3f inputs:diffuseColor = (1, 0, 0)
                token outputs:surface
            }
        }
        def Material "Blue"
        {
            token outputs:surface.connect = </Asset/Looks/Blue/S.outputs:surface>
            def Shader "S"
            {
                uniform token info:id = "UsdPreviewSurface"
                color3f inputs:diffuseColor = (0, 0, 1)
                token outputs:surface
            }
        }
        def Material "Tex"
        {
            token outputs:surface.connect = </Asset/Looks/Tex/S.outputs:surface>
            def Shader "S"
            {
                uniform token info:id = "UsdPreviewSurface"
                color3f inputs:diffuseColor.connect = </Asset/Looks/Tex/Img.outputs:rgb>
                token outputs:surface
            }
            def Shader "Img"
            {
                uniform token info:id = "UsdUVTexture"
                asset inputs:file = @./tex/checker.png@
                float2 inputs:st.connect = </Asset/Looks/Tex/T2.outputs:result>
                float3 outputs:rgb
            }
            def Shader "T2"
            {
                uniform token info:id = "UsdTransform2d"
                float2 inputs:in.connect = </Asset/Looks/Tex/Reader.outputs:result>
                float2 inputs:scale = (2, 2)
                float2 outputs:result
            }
            def Shader "Reader"
            {
                uniform token info:id = "UsdPrimvarReader_float2"
                token inputs:varname = "st"
                float2 outputs:result
            }
        }
    }

    variantSet "look" = {
        "red" {
            over "Quad"
            {
                rel material:binding = </Asset/Looks/Red>
            }
        }
        "blue" {
            over "Quad"
            {
                rel material:binding = </Asset/Looks/Blue>
            }
        }
    }
}
""".trimIndent()

    private val scene = """
#usda 1.0
(
    upAxis = "Z"
)

class "_Base"
{
    double3 xformOp:scale = (2, 2, 2)
}

def Xform "World"
{
    def "A" (
        references = @assets/asset.usda@
        inherits = </_Base>
    )
    {
        double3 xformOp:translate = (10, 0, 0)
        uniform token[] xformOpOrder = ["xformOp:translate", "xformOp:scale"]
    }

    def "B" (
        references = @assets/asset.usda@</Asset>
        variants = {
            string look = "blue"
        }
    )
    {
        float xformOp:rotateZ = 90
        uniform token[] xformOpOrder = ["xformOp:rotateZ"]
    }

    over "Ghost"
    {
        def Cube "NotDefined" {}
    }

    def Cube "Hidden"
    {
        token visibility = "invisible"
    }

    def Cube "Inactive" (
        active = false
    )
    {
    }
}
""".trimIndent()

    @Test
    fun usdaCompositionVariantsMaterialsAndTransforms() {
        val files = mapOf(
            "scene.usda" to scene.toByteArray(),
            "assets/asset.usda" to asset.toByteArray(),
            "assets/tex/checker.png" to ByteArray(16)
        )
        val sink = load(files, "scene.usda")
        assertEquals(4, sink.tris)
        assertEquals(listOf("assets/tex/checker.png|false"), sink.textures.distinct())
        assertEquals(2, sink.texTris)
        assertEquals(1, sink.colors[0xFFFF0000.toInt()])
        assertEquals(1, sink.colors[0xFF0000FF.toInt()])
        assertEquals(2f, sink.uvs.maxOrNull()!!, 1e-5f)
        assertEquals(-1f, sink.uvs.minOrNull()!!, 1e-5f)
        assertEquals(-1f, sink.mn[0], 1e-5f)
        assertEquals(12f, sink.mx[0], 1e-5f)
        assertEquals(0f, sink.mn[1], 1e-5f)
        assertEquals(0f, sink.mx[1], 1e-5f)
        assertEquals(-2f, sink.mn[2], 1e-5f)
        assertEquals(0f, sink.mx[2], 1e-5f)
    }

    @Test
    fun materialXDocumentReference() {
        val mtlx = """
<?xml version="1.0"?>
<materialx version="1.38" fileprefix="tex/">
  <nodegraph name="NG">
    <image name="base" type="color3">
      <input name="file" type="filename" value="wood.jpg" />
    </image>
    <output name="out_color" type="color3" nodename="base" />
  </nodegraph>
  <standard_surface name="SS" type="surfaceshader">
    <input name="base_color" type="color3" nodegraph="NG" output="out_color" />
  </standard_surface>
  <surfacematerial name="M_Wood" type="material">
    <input name="surfaceshader" type="surfaceshader" nodename="SS" />
  </surfacematerial>
  <standard_surface name="Plain" type="surfaceshader">
    <input name="base_color" type="color3" value="0, 1, 0" />
    <input name="base" type="float" value="1" />
  </standard_surface>
  <surfacematerial name="M_Plain" type="material">
    <input name="surfaceshader" type="surfaceshader" nodename="Plain" />
  </surfacematerial>
</materialx>
""".trim()
        val usda = """
#usda 1.0
def Xform "Root"
{
    def Scope "Materials" (
        prepend references = @./looks.mtlx@</MaterialX/Materials>
    )
    {
    }
    def Mesh "Wood"
    {
        point3f[] points = [(0, 0, 0), (1, 0, 0), (0, 1, 0)]
        int[] faceVertexCounts = [3]
        int[] faceVertexIndices = [0, 1, 2]
        texCoord2f[] primvars:st = [(0, 0), (1, 0), (0, 1)] (
            interpolation = "faceVarying"
        )
        rel material:binding = </Root/Materials/M_Wood>
    }
    def Mesh "Plain"
    {
        point3f[] points = [(0, 0, 0), (1, 0, 0), (0, 1, 0)]
        int[] faceVertexCounts = [3]
        int[] faceVertexIndices = [0, 1, 2]
        rel material:binding = </Root/Materials/M_Plain>
    }
}
""".trim()
        val sink = load(mapOf("a.usda" to usda.toByteArray(), "looks.mtlx" to mtlx.toByteArray(), "tex/wood.jpg" to ByteArray(16)), "a.usda")
        assertEquals(2, sink.tris)
        assertEquals(listOf("tex/wood.jpg|false"), sink.textures)
        assertEquals(1, sink.texTris)
        assertEquals(1, sink.colors[0xFF00FF00.toInt()])
    }

    @Test
    fun skeletalPoseAtStartTimeWithBlendShapes() {
        val usda = """
#usda 1.0
(
    startTimeCode = 1
)
def SkelRoot "Root"
{
    double3 xformOp:translate = (5, 0, 0)
    uniform token[] xformOpOrder = ["xformOp:translate"]

    def Skeleton "Skel"
    {
        uniform token[] joints = ["A", "A/B"]
        uniform matrix4d[] bindTransforms = [( (1, 0, 0, 0), (0, 1, 0, 0), (0, 0, 1, 0), (0, 0, 0, 1) ), ( (1, 0, 0, 0), (0, 1, 0, 0), (0, 0, 1, 0), (0, 1, 0, 1) )]
        uniform matrix4d[] restTransforms = [( (1, 0, 0, 0), (0, 1, 0, 0), (0, 0, 1, 0), (0, 0, 0, 1) ), ( (1, 0, 0, 0), (0, 1, 0, 0), (0, 0, 1, 0), (0, 1, 0, 1) )]
        rel skel:animationSource = </Root/Anim>
    }

    def SkelAnimation "Anim"
    {
        uniform token[] joints = ["A/B"]
        float3[] translations.timeSamples = {
            0: [(0, 9, 0)],
            1: [(0, 1, 0)],
        }
        quatf[] rotations.timeSamples = {
            1: [(0.70710677, 0, 0, 0.70710677)],
            2: [(1, 0, 0, 0)],
        }
        half3[] scales = [(1, 1, 1)]
        uniform token[] blendShapes = ["Up"]
        float[] blendShapeWeights = [0.5]
    }

    def Mesh "Mesh"
    {
        double3 xformOp:translate = (100, 0, 0)
        uniform token[] xformOpOrder = ["xformOp:translate"]
        point3f[] points = [(0, 1, 0), (0, 2, 0), (1, 2, 0)]
        int[] faceVertexCounts = [3]
        int[] faceVertexIndices = [0, 1, 2]
        uniform token subdivisionScheme = "none"
        int[] primvars:skel:jointIndices = [0, 1, 1] (
            elementSize = 1
            interpolation = "vertex"
        )
        float[] primvars:skel:jointWeights = [1, 1, 1] (
            elementSize = 1
            interpolation = "vertex"
        )
        rel skel:skeleton = </Root/Skel>
        uniform token[] skel:blendShapes = ["Up"]
        rel skel:blendShapeTargets = [</Root/Mesh/Up>]

        def BlendShape "Up"
        {
            uniform vector3f[] offsets = [(0, 0, 1)]
            uniform int[] pointIndices = [0]
        }
    }
}
""".trim()
        val sink = load(mapOf("s.usda" to usda.toByteArray()), "s.usda")
        assertEquals(1, sink.tris)
        val expected = listOf(floatArrayOf(5f, 1f, 0.5f), floatArrayOf(4f, 1f, 0f), floatArrayOf(4f, 2f, 0f))
        for (e in expected) {
            assertTrue(sink.points.any { p -> (0 until 3).all { kotlin.math.abs(p[it] - e[it]) < 1e-5f } })
        }
    }
}
