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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanRoomFbxAnimationTest {

    private fun near(expected: FloatArray, actual: FloatArray, at: Int, tol: Float = 1e-4f) {
        for (k in expected.indices) assertEquals("component $k of ${actual.copyOfRange(at, at + expected.size).toList()}", expected[k], actual[at + k], tol)
    }

    @Test
    fun fbxCurvesDriveModelTransforms() {
        val fbx = """
; FBX 7.4.0 project file
FBXHeaderExtension:  {
	FBXVersion: 7400
}
Objects:  {
	Geometry: 100, "Geometry::Tri", "Mesh" {
		Vertices: *9 {
 a: 0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0,0.0
}
		PolygonVertexIndex: *3 {
 a: 0,1,-3
}
	}
	Model: 200, "Model::Tri", "Mesh" {
		Properties70:  {
			P: "Lcl Translation", "Lcl Translation", "", "A", 0.0, 0.0, 0.0
		}
	}
	AnimationStack: 300, "AnimStack::Take", "" {
		Properties70:  {
			P: "LocalStart", "KTime", "Time", "", 0
			P: "LocalStop", "KTime", "Time", "", 46186158000
		}
	}
	AnimationLayer: 301, "AnimLayer::Base", "" {
	}
	AnimationCurveNode: 302, "AnimCurveNode::T", "" {
		Properties70:  {
			P: "d|X", "Number", "", "A", 0.0
			P: "d|Y", "Number", "", "A", 0.0
			P: "d|Z", "Number", "", "A", 0.0
		}
	}
	AnimationCurve: 303, "AnimCurve::", "" {
		KeyTime: *2 {
 a: 0,46186158000
}
		KeyValueFloat: *2 {
 a: 0.0,8.0
}
		KeyAttrFlags: *1 {
 a: 260
}
		KeyAttrDataFloat: *4 {
 a: 0,0,0,0
}
		KeyAttrRefCount: *1 {
 a: 2
}
	}
	AnimationCurve: 304, "AnimCurve::", "" {
		KeyTime: *2 {
 a: 0,46186158000
}
		KeyValueFloat: *2 {
 a: 0.0,4.0
}
		KeyAttrFlags: *1 {
 a: 2
}
		KeyAttrDataFloat: *4 {
 a: 0,0,0,0
}
		KeyAttrRefCount: *1 {
 a: 2
}
	}
}
Connections:  {
	C: "OO", 200, 0
	C: "OO", 100, 200
	C: "OO", 301, 300
	C: "OO", 302, 301
	C: "OP", 302, 200, "Lcl Translation"
	C: "OP", 303, 302, "d|X"
	C: "OP", 304, 302, "d|Y"
}
""".trim()
        val root = FbxReader.parse(fbx.toByteArray())
        assertNotNull(root)
        val sink = AnimSink()
        CleanRoomFbx(root!!, sink).load()
        assertEquals(1, sink.polys)
        val half = sink.pose(0.5f)
        near(floatArrayOf(4f, 0f, 0f, 5f, 0f, 0f, 4f, 1f, 0f), half, 0, 1e-3f)
        near(floatArrayOf(8f, 4f, 0f), sink.pose(1f), 0, 1e-3f)
    }

    @Test
    fun fbxStacksBecomeClipsAndLayersBlend() {
        fun curve(id: Int, flags: Int, v0: Float, v1: Float) = """
	AnimationCurve: $id, "AnimCurve::", "" {
		KeyTime: *2 {
 a: 0,46186158000
}
		KeyValueFloat: *2 {
 a: $v0,$v1
}
		KeyAttrFlags: *1 {
 a: $flags
}
		KeyAttrDataFloat: *4 {
 a: 0,0,0,0
}
		KeyAttrRefCount: *1 {
 a: 2
}
	}"""
        fun node(id: Int) = """
	AnimationCurveNode: $id, "AnimCurveNode::T", "" {
		Properties70:  {
			P: "d|X", "Number", "", "A", 0.0
			P: "d|Y", "Number", "", "A", 0.0
			P: "d|Z", "Number", "", "A", 0.0
		}
	}"""
        fun stack(id: Int, name: String) = """
	AnimationStack: $id, "AnimStack::$name", "" {
		Properties70:  {
			P: "LocalStart", "KTime", "Time", "", 0
			P: "LocalStop", "KTime", "Time", "", 46186158000
		}
	}"""
        val fbx = """
; FBX 7.4.0 project file
FBXHeaderExtension:  {
	FBXVersion: 7400
}
Objects:  {
	Geometry: 100, "Geometry::Tri", "Mesh" {
		Vertices: *9 {
 a: 0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0,0.0
}
		PolygonVertexIndex: *3 {
 a: 0,1,-3
}
	}
	Model: 200, "Model::Tri", "Mesh" {
	}${stack(300, "Walk")}
	AnimationLayer: 301, "AnimLayer::Base", "" {
	}${node(302)}${curve(303, 260, 0f, 8f)}${stack(310, "Lift")}
	AnimationLayer: 311, "AnimLayer::Base", "" {
	}
	AnimationLayer: 312, "AnimLayer::Extra", "" {
		Properties70:  {
			P: "Weight", "Number", "", "A", 50
			P: "BlendMode", "enum", "", "", 0
		}
	}${node(313)}${curve(314, 260, 0f, 4f)}${node(315)}${curve(316, 2, 2f, 2f)}
}
Connections:  {
	C: "OO", 200, 0
	C: "OO", 100, 200
	C: "OO", 301, 300
	C: "OO", 302, 301
	C: "OP", 302, 200, "Lcl Translation"
	C: "OP", 303, 302, "d|X"
	C: "OO", 311, 310
	C: "OO", 312, 310
	C: "OO", 313, 311
	C: "OP", 313, 200, "Lcl Translation"
	C: "OP", 314, 313, "d|Y"
	C: "OO", 315, 312
	C: "OP", 315, 200, "Lcl Translation"
	C: "OP", 316, 315, "d|Y"
}
""".trim()
        val sink = AnimSink()
        CleanRoomFbx(FbxReader.parse(fbx.toByteArray())!!, sink).load()
        val a = sink.animation()!!
        assertEquals(listOf("Walk", "Lift"), (0 until a.clipCount).map { a.clipName(it) })
        near(floatArrayOf(8f, 0f, 0f), sink.pose(1f, 0), 0, 1e-3f)
        near(floatArrayOf(0f, 5f, 0f), sink.pose(1f, 1), 0, 1e-3f)
        near(floatArrayOf(0f, 3f, 0f), sink.pose(0.5f, 1), 0, 1e-3f)
    }

    @Test
    fun fbxSkinnedRestPoseMatchesStaticGeometry() {
        val root = FbxReader.parse(javaClass.classLoader!!.getResourceAsStream("fbx/huesitos.fbx")!!.readBytes())
        val sink = AnimSink()
        CleanRoomFbx(root!!, sink).load()
        assertEquals(120, sink.polys)
        val static = sink.verts.toFloatArray()
        val start = sink.pose(0f)
        for (i in static.indices) assertEquals(static[i], start[i], 1e-2f)
        val mid = sink.pose(0.4f)
        assertTrue(static.indices.any { kotlin.math.abs(static[it] - mid[it]) > 1f })
        assertTrue(mid.all { it.isFinite() && kotlin.math.abs(it) < 2000f })
    }
}
