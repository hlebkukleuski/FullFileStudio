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

package me.hlebkukleuski.fullfilestudio.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SceneFormatsTest {

    private class Collector : TriangleSink {
        val points = ArrayList<FloatArray>()
        val colors = ArrayList<Int>()
        override fun triangle(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float, color: Int): Boolean {
            points.add(floatArrayOf(x1, y1, z1)); points.add(floatArrayOf(x2, y2, z2)); points.add(floatArrayOf(x3, y3, z3))
            colors.add(color)
            return true
        }
        val triangles get() = colors.size
        fun min(axis: Int) = points.minOf { it[axis] }
        fun max(axis: Int) = points.maxOf { it[axis] }
    }

    private val cubeMesh = """
        <mesh><vertices>
          <vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="1" y="1" z="0"/><vertex x="0" y="1" z="0"/>
          <vertex x="0" y="0" z="1"/><vertex x="1" y="0" z="1"/><vertex x="1" y="1" z="1"/><vertex x="0" y="1" z="1"/>
        </vertices><triangles>
          <triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="3" v3="2"/>
          <triangle v1="4" v2="5" v3="6"/><triangle v1="4" v2="6" v3="7"/>
          <triangle v1="0" v2="1" v3="5"/><triangle v1="0" v2="5" v3="4"/>
          <triangle v1="1" v2="2" v3="6" p1="1"/><triangle v1="1" v2="6" v3="5"/>
          <triangle v1="2" v2="3" v3="7"/><triangle v1="2" v2="7" v3="6"/>
          <triangle v1="3" v2="0" v3="4"/><triangle v1="3" v2="4" v3="7"/>
        </triangles></mesh>
    """.trimIndent()

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun threeMfAppliesBuildItemAndComponentTransforms() {
        val model = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
              <resources>
                <basematerials id="5"><base name="red" displaycolor="#FF0000"/><base name="blue" displaycolor="#0000FFFF"/></basematerials>
                <object id="1" type="model" pid="5" pindex="0">$cubeMesh</object>
                <object id="2" type="model"><components>
                  <component objectid="1"/>
                  <component objectid="1" transform="1 0 0 0 1 0 0 0 1 10 0 0"/>
                </components></object>
              </resources>
              <build>
                <item objectid="2" transform="2 0 0 0 2 0 0 0 2 0 0 5"/>
                <item objectid="1" transform="0 1 0 -1 0 0 0 0 1 -3 0 0"/>
              </build>
            </model>"""
        val c = Collector()
        assertTrue(SceneFormatsParser.parse3mf(mapOf("3D/3dmodel.model" to bytes(model)), null, c))
        assertEquals(36, c.triangles)
        assertEquals(-4f, c.min(0), 1e-5f)
        assertEquals(22f, c.max(0), 1e-5f)
        assertEquals(0f, c.min(1), 1e-5f)
        assertEquals(2f, c.max(1), 1e-5f)
        assertEquals(0f, c.min(2), 1e-5f)
        assertEquals(7f, c.max(2), 1e-5f)
        assertEquals(0xFFFF0000.toInt(), c.colors[0])
        assertEquals(0xFF0000FF.toInt(), c.colors[6])
        val rotated = c.points.subList(24 * 3, 36 * 3)
        assertTrue(rotated.all { it[0] in -4.0001f..-2.9999f && it[1] in -0.0001f..1.0001f })
    }

    @Test
    fun threeMfFollowsProductionExtensionPathsAndRootRelationship() {
        val rels = """<?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Target="/3D/main.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
            </Relationships>"""
        val main = """<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06">
              <resources><object id="7"><components><component objectid="3" p:path="/3D/Objects/part.model" transform="1 0 0 0 1 0 0 0 1 0 100 0"/></components></object></resources>
              <build><item objectid="7"/></build></model>"""
        val part = """<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="3">$cubeMesh</object></resources><build/></model>"""
        val decoy = """<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="1">$cubeMesh</object></resources><build><item objectid="1"/></build></model>"""
        val c = Collector()
        SceneFormatsParser.parse3mf(
            linkedMapOf("3D/3dmodel.model" to bytes(decoy), "3D/Objects/part.model" to bytes(part), "3D/main.model" to bytes(main)),
            SceneFormatsParser.find3mfRootModel(bytes(rels)),
            c
        )
        assertEquals(12, c.triangles)
        assertEquals(100f, c.min(1), 1e-5f)
        assertEquals(101f, c.max(1), 1e-5f)
    }

    @Test
    fun threeMfStopsOnRecursiveComponentsAndBadIndices() {
        val model = """<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>
            <object id="1"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/></vertices>
              <triangles><triangle v1="0" v2="1" v3="2"/><triangle v1="0" v2="1" v3="9"/></triangles></mesh>
              <components><component objectid="1"/></components></object>
            </resources><build><item objectid="1"/></build></model>"""
        val c = Collector()
        SceneFormatsParser.parse3mf(mapOf("3D/3dmodel.model" to bytes(model)), null, c)
        assertEquals(33, c.triangles)
    }

    private fun dae(upAxis: String, scene: String) = """<?xml version="1.0" encoding="utf-8"?>
        <COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
          <asset><up_axis>$upAxis</up_axis></asset>
          <library_effects><effect id="green-fx"><profile_COMMON><technique sid="t"><lambert><diffuse><color>0 1 0 1</color></diffuse></lambert></technique></profile_COMMON></effect></library_effects>
          <library_materials><material id="green"><instance_effect url="#green-fx"/></material></library_materials>
          <library_geometries><geometry id="quad"><mesh>
            <source id="quad-pos"><float_array id="quad-pos-array" count="12">0 0 0 1 0 0 1 1 0 0 1 0</float_array></source>
            <source id="quad-nrm"><float_array id="quad-nrm-array" count="3">0 0 1</float_array></source>
            <vertices id="quad-vtx"><input semantic="POSITION" source="#quad-pos"/></vertices>
            <polylist material="mat" count="1"><input semantic="VERTEX" source="#quad-vtx" offset="0"/><input semantic="NORMAL" source="#quad-nrm" offset="1"/>
              <vcount>4</vcount><p>0 0 1 0 2 0 3 0</p></polylist>
          </mesh></geometry></library_geometries>
          <library_nodes><node id="lib-quad"><translate>0 0 50</translate><instance_geometry url="#quad"/></node></library_nodes>
          <library_visual_scenes><visual_scene id="Scene">$scene</visual_scene></library_visual_scenes>
          <scene><instance_visual_scene url="#Scene"/></scene>
        </COLLADA>"""

    @Test
    fun colladaComposesNodeTransformsAndBindsMaterials() {
        val scene = """
            <node id="a"><matrix>1 0 0 10 0 1 0 20 0 0 1 30 0 0 0 1</matrix>
              <instance_geometry url="#quad"><bind_material><technique_common><instance_material symbol="mat" target="#green"/></technique_common></bind_material></instance_geometry>
              <node id="b"><scale>2 3 1</scale><instance_geometry url="#quad"/></node>
            </node>
            <node id="c"><rotate>0 0 1 90</rotate><instance_node url="#lib-quad"/></node>"""
        val c = Collector()
        val result = SceneFormatsParser.parseCollada(bytes(dae("Z_UP", scene)), c)
        assertFalse(result.isYUp)
        assertEquals(6, c.triangles)
        assertEquals(0xFF00FF00.toInt(), c.colors[0])
        assertEquals(0, c.colors[2])
        val first = c.points.subList(0, 6)
        assertTrue(first.all { it[0] in 10f..11f && it[1] in 20f..21f && it[2] == 30f })
        val scaled = c.points.subList(6, 12)
        assertEquals(12f, scaled.maxOf { it[0] }, 1e-5f)
        assertEquals(23f, scaled.maxOf { it[1] }, 1e-5f)
        val rotated = c.points.subList(12, 18)
        assertEquals(-1f, rotated.minOf { it[0] }, 1e-5f)
        assertEquals(0f, rotated.maxOf { it[0] }, 1e-5f)
        assertEquals(1f, rotated.maxOf { it[1] }, 1e-5f)
        assertTrue(rotated.all { it[2] == 50f })
    }

    @Test
    fun colladaReportsUpAxisAndRotatesXUpToZUp() {
        val node = """<node><instance_geometry url="#quad"/></node>"""
        assertTrue(SceneFormatsParser.parseCollada(bytes(dae("Y_UP", node)), Collector()).isYUp)
        val c = Collector()
        assertFalse(SceneFormatsParser.parseCollada(bytes(dae("X_UP", node)), c).isYUp)
        assertEquals(1f, c.max(2), 1e-5f)
        assertEquals(0f, c.max(1), 1e-5f)
        assertEquals(1f, c.max(0), 1e-5f)
    }

    @Test
    fun colladaWithoutSceneDrawsAllGeometry() {
        val c = Collector()
        SceneFormatsParser.parseCollada(bytes(dae("Z_UP", "").replace("<scene><instance_visual_scene url=\"#Scene\"/></scene>", "")), c)
        assertEquals(2, c.triangles)
    }
}
