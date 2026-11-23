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

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CleanRoomAnimationTest {

    private fun near(expected: FloatArray, actual: FloatArray, at: Int, tol: Float = 1e-4f) {
        for (k in expected.indices) assertEquals("component $k of ${actual.copyOfRange(at, at + expected.size).toList()}", expected[k], actual[at + k], tol)
    }

    private fun floats(vararg v: Float): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return bb.array()
    }

    private class Gltf {
        val buffer = ByteArrayOutputStream()
        val views = org.json.JSONArray()
        val accessors = org.json.JSONArray()

        fun accessor(data: ByteArray, count: Int, type: String, componentType: Int = 5126): Int {
            while (buffer.size() % 4 != 0) buffer.write(0)
            views.put(JSONObject().put("buffer", 0).put("byteOffset", buffer.size()).put("byteLength", data.size))
            buffer.write(data)
            accessors.put(JSONObject().put("bufferView", views.length() - 1).put("componentType", componentType).put("count", count).put("type", type))
            return accessors.length() - 1
        }

        fun json(root: JSONObject): JSONObject {
            val b64 = android.util.Base64.encodeToString(buffer.toByteArray(), android.util.Base64.NO_WRAP)
            root.put("buffers", org.json.JSONArray().put(JSONObject().put("byteLength", buffer.size()).put("uri", "data:application/octet-stream;base64,$b64")))
            root.put("bufferViews", views)
            root.put("accessors", accessors)
            return root
        }
    }

    @Test
    fun gltfSamplersMorphAndSkin() {
        val g = Gltf()
        val pos = g.accessor(floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), 3, "VEC3")
        val uv = g.accessor(floats(0f, 0f, 1f, 0f, 0f, 1f), 3, "VEC2")
        val times = g.accessor(floats(0f, 1f), 2, "SCALAR")
        val tOut = g.accessor(floats(0f, 0f, 0f, 2f, 0f, 0f), 2, "VEC3")
        val s = kotlin.math.sqrt(0.5f)
        val rOut = g.accessor(floats(0f, 0f, 0f, 1f, 0f, 0f, s, s), 2, "VEC4")
        val stepOut = g.accessor(floats(0f, 0f, 0f, 0f, 3f, 0f), 2, "VEC3")
        val cubicOut = g.accessor(floats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 4f, 0f, 0f, 0f, 0f, 0f), 6, "VEC3")
        val morphDelta = g.accessor(floats(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f), 3, "VEC3")
        val wOut = g.accessor(floats(0f, 1f), 2, "SCALAR")
        val skinPos = g.accessor(floats(0f, 2f, 0f, 1f, 2f, 0f, 0f, 3f, 0f), 3, "VEC3")
        val joints = g.accessor(byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0), 3, "VEC4", 5121)
        val weights = g.accessor(floats(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f), 3, "VEC4")
        val ibm = g.accessor(floats(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 1f), 2, "MAT4")
        val jointRot = g.accessor(floats(0f, 0f, 0f, 1f, 0f, 0f, s, s), 2, "VEC4")
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF8080FF.toInt()) }.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val pngB64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        fun prim(extra: JSONObject.() -> Unit = {}) = JSONObject().put("attributes", JSONObject().put("POSITION", pos).put("TEXCOORD_0", uv)).apply(extra)
        val root = JSONObject()
            .put("images", org.json.JSONArray().put(JSONObject().put("uri", "data:image/png;base64,$pngB64")))
            .put("textures", org.json.JSONArray().put(JSONObject().put("source", 0)))
            .put("materials", org.json.JSONArray().put(JSONObject().put("normalTexture", JSONObject().put("index", 0).put("scale", 0.5))))
            .put("meshes", org.json.JSONArray()
                .put(JSONObject().put("primitives", org.json.JSONArray().put(prim())))
                .put(JSONObject().put("primitives", org.json.JSONArray().put(prim { put("targets", org.json.JSONArray().put(JSONObject().put("POSITION", morphDelta))) })).put("weights", org.json.JSONArray().put(0)))
                .put(JSONObject().put("primitives", org.json.JSONArray().put(JSONObject().put("attributes", JSONObject().put("POSITION", skinPos).put("JOINTS_0", joints).put("WEIGHTS_0", weights)))))
            )
            .put("nodes", org.json.JSONArray()
                .put(JSONObject().put("mesh", 0))
                .put(JSONObject().put("mesh", 0))
                .put(JSONObject().put("mesh", 0))
                .put(JSONObject().put("mesh", 0))
                .put(JSONObject().put("mesh", 1))
                .put(JSONObject().put("mesh", 2).put("skin", 0))
                .put(JSONObject().put("children", org.json.JSONArray().put(7)))
                .put(JSONObject().put("translation", org.json.JSONArray().put(0).put(1).put(0)))
            )
            .put("skins", org.json.JSONArray().put(JSONObject().put("joints", org.json.JSONArray().put(6).put(7)).put("inverseBindMatrices", ibm)))
            .put("scenes", org.json.JSONArray().put(JSONObject().put("nodes", org.json.JSONArray().put(0).put(1).put(2).put(3).put(4).put(5).put(6))))
            .put("animations", org.json.JSONArray().put(JSONObject()
                .put("samplers", org.json.JSONArray()
                    .put(JSONObject().put("input", times).put("output", tOut))
                    .put(JSONObject().put("input", times).put("output", rOut))
                    .put(JSONObject().put("input", times).put("output", stepOut).put("interpolation", "STEP"))
                    .put(JSONObject().put("input", times).put("output", cubicOut).put("interpolation", "CUBICSPLINE"))
                    .put(JSONObject().put("input", times).put("output", wOut))
                    .put(JSONObject().put("input", times).put("output", jointRot))
                )
                .put("channels", org.json.JSONArray()
                    .put(JSONObject().put("sampler", 0).put("target", JSONObject().put("node", 0).put("path", "translation")))
                    .put(JSONObject().put("sampler", 1).put("target", JSONObject().put("node", 1).put("path", "rotation")))
                    .put(JSONObject().put("sampler", 2).put("target", JSONObject().put("node", 2).put("path", "translation")))
                    .put(JSONObject().put("sampler", 3).put("target", JSONObject().put("node", 3).put("path", "translation")))
                    .put(JSONObject().put("sampler", 4).put("target", JSONObject().put("node", 4).put("path", "weights")))
                    .put(JSONObject().put("sampler", 5).put("target", JSONObject().put("node", 7).put("path", "rotation")))
                )
            ))
        val json = g.json(root)
        json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0).put("material", 0)
        val sink = AnimSink()
        CleanRoomGltf(json, null, 0L, sink, 100_000).load()
        assertEquals(6, sink.polys)
        assertEquals(listOf("gltf:0|false|0.5"), sink.normalTextures)
        assertTrue(sink.normalTex.take(4).all { it >= 1000 })
        val anim = assertNotNull(sink.animation()).let { sink.animation()!! }
        assertEquals(1f, anim.duration, 1e-3f)
        val half = sink.pose(0.5f)
        near(floatArrayOf(1f, 0f, 0f, 2f, 0f, 0f, 1f, 1f, 0f), half, 0)
        val c = kotlin.math.cos(Math.PI / 4).toFloat()
        near(floatArrayOf(0f, 0f, 0f, c, c, 0f, -c, c, 0f), half, 9, 2e-3f)
        near(floatArrayOf(0f, 0f, 0f), half, 18)
        near(floatArrayOf(2f, 0f, 0f), half, 27, 1e-3f)
        near(floatArrayOf(0f, 0f, 0.5f), half, 36, 1e-3f)
        val end = sink.pose(1f)
        near(floatArrayOf(0f, 3f, 0f), end, 18)
        near(floatArrayOf(-1f, 1f, 0f, -1f, 2f, 0f, -2f, 1f, 0f), end, 45, 1e-3f)
        near(floatArrayOf(0f, 2f, 0f, 1f, 2f, 0f, 0f, 3f, 0f), sink.pose(0f), 45)
    }

    @Test
    fun usdTransformPointCacheAndSkeletonSamples() {
        val usda = """
#usda 1.0
(
    startTimeCode = 0
    endTimeCode = 24
    timeCodesPerSecond = 24
)
def Xform "Mover"
{
    double3 xformOp:translate.timeSamples = {
        0: (0, 0, 0),
        24: (6, 0, 0),
    }
    uniform token[] xformOpOrder = ["xformOp:translate"]
    def Mesh "Tri"
    {
        point3f[] points = [(0, 0, 0), (1, 0, 0), (0, 1, 0)]
        int[] faceVertexCounts = [3]
        int[] faceVertexIndices = [0, 1, 2]
        uniform token subdivisionScheme = "none"
    }
}
def Mesh "Cache"
{
    point3f[] points.timeSamples = {
        0: [(10, 0, 0), (11, 0, 0), (10, 1, 0)],
        24: [(10, 0, 2), (11, 0, 2), (10, 1, 2)],
    }
    int[] faceVertexCounts = [3]
    int[] faceVertexIndices = [0, 1, 2]
    uniform token subdivisionScheme = "none"
}
def SkelRoot "Rig"
{
    def Skeleton "Skel"
    {
        uniform token[] joints = ["A", "A/B"]
        uniform matrix4d[] bindTransforms = [( (1, 0, 0, 0), (0, 1, 0, 0), (0, 0, 1, 0), (0, 0, 0, 1) ), ( (1, 0, 0, 0), (0, 1, 0, 0), (0, 0, 1, 0), (0, 1, 0, 1) )]
        uniform matrix4d[] restTransforms = [( (1, 0, 0, 0), (0, 1, 0, 0), (0, 0, 1, 0), (0, 0, 0, 1) ), ( (1, 0, 0, 0), (0, 1, 0, 0), (0, 0, 1, 0), (0, 1, 0, 1) )]
        rel skel:animationSource = </Rig/Anim>
    }
    def SkelAnimation "Anim"
    {
        uniform token[] joints = ["A/B"]
        float3[] translations = [(0, 1, 0)]
        quatf[] rotations.timeSamples = {
            0: [(1, 0, 0, 0)],
            24: [(0.70710677, 0, 0, 0.70710677)],
        }
        half3[] scales = [(1, 1, 1)]
    }
    def Mesh "Arm"
    {
        point3f[] points = [(0, 2, 0), (1, 2, 0), (0, 3, 0)]
        int[] faceVertexCounts = [3]
        int[] faceVertexIndices = [0, 1, 2]
        uniform token subdivisionScheme = "none"
        int[] primvars:skel:jointIndices = [1, 1, 1] (
            elementSize = 1
            interpolation = "vertex"
        )
        float[] primvars:skel:jointWeights = [1, 1, 1] (
            elementSize = 1
            interpolation = "vertex"
        )
        rel skel:skeleton = </Rig/Skel>
    }
}
""".trim()
        val sink = AnimSink()
        CleanRoomUsd(UsdStage({ if (it == "a.usda") usda.toByteArray() else null }, "a.usda"), sink).load()
        assertEquals(3, sink.polys)
        assertEquals(1f, assertNotNull(sink.animation()).let { sink.animation()!!.duration }, 1e-3f)
        val half = sink.pose(0.5f)
        near(floatArrayOf(3f, 0f, 0f, 4f, 0f, 0f, 3f, 1f, 0f), half, 0, 1e-3f)
        near(floatArrayOf(10f, 0f, 1f, 11f, 0f, 1f, 10f, 1f, 1f), half, 9, 1e-3f)
        val end = sink.pose(1f)
        near(floatArrayOf(-1f, 1f, 0f, -1f, 2f, 0f, -2f, 1f, 0f), end, 18, 1e-3f)
        near(floatArrayOf(0f, 2f, 0f, 1f, 2f, 0f, 0f, 3f, 0f), sink.pose(0f), 18, 1e-3f)
    }

    @Test
    fun colladaTexturesSkinAndClips() {
        fun anim(id: String, out: String) = """
    <animation id="$id">
      <source id="$id-in"><float_array id="$id-in-a" count="2">0 1</float_array><technique_common><accessor source="#$id-in-a" count="2" stride="1"><param name="TIME" type="float"/></accessor></technique_common></source>
      <source id="$id-out"><float_array id="$id-out-a" count="6">$out</float_array><technique_common><accessor source="#$id-out-a" count="2" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common></source>
      <source id="$id-int"><Name_array id="$id-int-a" count="2">LINEAR LINEAR</Name_array><technique_common><accessor source="#$id-int-a" count="2" stride="1"><param name="INTERPOLATION" type="name"/></accessor></technique_common></source>
      <sampler id="$id-s"><input semantic="INPUT" source="#$id-in"/><input semantic="OUTPUT" source="#$id-out"/><input semantic="INTERPOLATION" source="#$id-int"/></sampler>
      <channel source="#$id-s" target="Bone/location"/>
    </animation>"""
        val dae = """<?xml version="1.0" encoding="utf-8"?>
<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
  <asset><up_axis>Y_UP</up_axis></asset>
  <library_images>
    <image id="diffuse_png"><init_from>tex/diffuse.png</init_from></image>
    <image id="normal_png"><init_from>tex/normal.png</init_from></image>
  </library_images>
  <library_effects>
    <effect id="fx">
      <profile_COMMON>
        <newparam sid="surf"><surface type="2D"><init_from>diffuse_png</init_from></surface></newparam>
        <newparam sid="samp"><sampler2D><source>surf</source></sampler2D></newparam>
        <newparam sid="nsurf"><surface type="2D"><init_from>normal_png</init_from></surface></newparam>
        <newparam sid="nsamp"><sampler2D><source>nsurf</source></sampler2D></newparam>
        <technique sid="common">
          <phong><diffuse><texture texture="samp" texcoord="UVMap"/></diffuse></phong>
          <extra><technique profile="FCOLLADA"><bump bumptype="NORMALMAP"><texture texture="nsamp" texcoord="UVMap"/></bump></technique></extra>
        </technique>
      </profile_COMMON>
    </effect>
  </library_effects>
  <library_materials><material id="mat"><instance_effect url="#fx"/></material></library_materials>
  <library_geometries>
    <geometry id="tri"><mesh>
      <source id="tri-pos"><float_array id="tri-pos-a" count="9">0 0 0 1 0 0 0 1 0</float_array><technique_common><accessor source="#tri-pos-a" count="3" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common></source>
      <source id="tri-uv"><float_array id="tri-uv-a" count="6">0 0 1 0 0 1</float_array><technique_common><accessor source="#tri-uv-a" count="3" stride="2"><param name="S" type="float"/><param name="T" type="float"/></accessor></technique_common></source>
      <vertices id="tri-v"><input semantic="POSITION" source="#tri-pos"/></vertices>
      <triangles material="m" count="1"><input semantic="VERTEX" source="#tri-v" offset="0"/><input semantic="TEXCOORD" source="#tri-uv" offset="1" set="0"/><p>0 0 1 1 2 2</p></triangles>
    </mesh></geometry>
  </library_geometries>
  <library_controllers>
    <controller id="skin"><skin source="#tri">
      <bind_shape_matrix>1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1</bind_shape_matrix>
      <source id="skin-j"><Name_array id="skin-j-a" count="1">Bone</Name_array><technique_common><accessor source="#skin-j-a" count="1" stride="1"><param name="JOINT" type="name"/></accessor></technique_common></source>
      <source id="skin-ib"><float_array id="skin-ib-a" count="16">1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1</float_array><technique_common><accessor source="#skin-ib-a" count="1" stride="16"><param name="TRANSFORM" type="float4x4"/></accessor></technique_common></source>
      <source id="skin-w"><float_array id="skin-w-a" count="1">1</float_array><technique_common><accessor source="#skin-w-a" count="1" stride="1"><param name="WEIGHT" type="float"/></accessor></technique_common></source>
      <joints><input semantic="JOINT" source="#skin-j"/><input semantic="INV_BIND_MATRIX" source="#skin-ib"/></joints>
      <vertex_weights count="3"><input semantic="JOINT" source="#skin-j" offset="0"/><input semantic="WEIGHT" source="#skin-w" offset="1"/><vcount>1 1 1</vcount><v>0 0 0 0 0 0</v></vertex_weights>
    </skin></controller>
  </library_controllers>
  <library_animations>${anim("moveX", "0 0 0 4 0 0")}${anim("moveY", "0 0 0 0 2 0")}
  </library_animations>
  <library_animation_clips>
    <animation_clip id="walk" name="walk" start="0" end="1"><instance_animation url="#moveX"/></animation_clip>
    <animation_clip id="jump" name="jump" start="0" end="1"><instance_animation url="#moveY"/></animation_clip>
  </library_animation_clips>
  <library_visual_scenes>
    <visual_scene id="scene">
      <node id="Armature" name="Armature">
        <node id="Bone" sid="Bone" name="Bone" type="JOINT"><translate sid="location">0 0 0</translate></node>
      </node>
      <node id="Mesh" name="Mesh">
        <instance_controller url="#skin"><skeleton>#Bone</skeleton>
          <bind_material><technique_common><instance_material symbol="m" target="#mat"><bind_vertex_input semantic="UVMap" input_semantic="TEXCOORD" input_set="0"/></instance_material></technique_common></bind_material>
        </instance_controller>
      </node>
    </visual_scene>
  </library_visual_scenes>
  <scene><instance_visual_scene url="#scene"/></scene>
</COLLADA>"""
        val files = mapOf("tex/diffuse.png" to byteArrayOf(1, 2, 3), "tex/normal.png" to byteArrayOf(4, 5, 6))
        val sink = AnimSink(files)
        CleanRoomCollada(CleanRoomCollada.parse(dae.toByteArray())!!, sink).load()
        assertEquals(1, sink.polys)
        assertEquals(listOf("dae:tex/diffuse.png:true"), sink.textures)
        assertEquals(listOf("dae:tex/normal.png|false|1.0"), sink.normalTextures)
        assertEquals(-2, sink.triBone[0])
        val a = sink.animation()!!
        assertEquals(listOf("walk", "jump"), (0 until a.clipCount).map { a.clipName(it) })
        near(floatArrayOf(4f, 0f, 0f, 5f, 0f, 0f, 4f, 1f, 0f), sink.pose(1f, 0), 0, 1e-3f)
        near(floatArrayOf(0f, 2f, 0f, 1f, 2f, 0f, 0f, 3f, 0f), sink.pose(1f, 1), 0, 1e-3f)
        near(floatArrayOf(0f, 1f, 0f), sink.pose(0.5f, 1), 0, 1e-3f)
    }

    @Test
    fun normalMapHeuristicSeparatesNormalAndHeightMaps() {
        fun png(color: (Int, Int) -> Int): ByteArray {
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            for (y in 0 until 16) for (x in 0 until 16) bmp.setPixel(x, y, color(x, y))
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        assertTrue(NormalMapHeuristic.looksLikeNormalMap(png { x, _ -> android.graphics.Color.rgb(128 + (x - 8) * 4, 128, 250) }))
        assertFalse(NormalMapHeuristic.looksLikeNormalMap(png { x, y -> android.graphics.Color.rgb((x + y) * 8, (x + y) * 8, (x + y) * 8) }))
    }
}
