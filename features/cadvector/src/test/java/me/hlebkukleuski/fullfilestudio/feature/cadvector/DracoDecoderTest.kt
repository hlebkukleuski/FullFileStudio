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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DracoDecoderTest {

    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("draco/$name")!!.readBytes()

    private fun check(name: String, skip: Set<Int>) {
        val ref = JSONObject(String(res("$name.drc.ref.json"), Charsets.UTF_8))
        val mesh = DracoDecoder.decodeOrThrow(res("$name.drc"))
        val faces = ref.getJSONArray("faces")
        assertEquals("$name faces", faces.length(), mesh.faces.size)
        val attrs = ref.getJSONObject("attributes")
        val ids = attrs.keys().asSequence().map { it.toInt() }.filter { it !in skip }.sorted().toList()
        fun corners(values: (Int, Int) -> FloatArray, f: IntArray): Array<FloatArray> = Array(3) { k ->
            ids.flatMap { uid -> values(uid, f[k]).toList() }.toFloatArray()
        }
        val refTris = (0 until faces.length() / 3).map { t ->
            corners({ uid, p ->
                val a = attrs.getJSONObject(uid.toString())
                val c = a.getInt("components")
                val v = a.getJSONArray("values")
                FloatArray(c) { v.getDouble(p * c + it).toFloat() }
            }, intArrayOf(faces.getInt(t * 3), faces.getInt(t * 3 + 1), faces.getInt(t * 3 + 2)))
        }
        val mineTris = (0 until mesh.faces.size / 3).map { t ->
            corners({ uid, p ->
                val a = mesh.attribute(uid)!!
                FloatArray(a.components) { a.values[p * a.components + it] }
            }, intArrayOf(mesh.faces[t * 3], mesh.faces[t * 3 + 1], mesh.faces[t * 3 + 2]))
        }
        fun same(x: FloatArray, y: FloatArray) = x.indices.all { kotlin.math.abs(x[it] - y[it]) <= 1e-4f + 1e-4f * kotlin.math.abs(x[it]) }
        val used = BooleanArray(mineTris.size)
        val byFirst = mineTris.indices.sortedBy { mineTris[it].minOf { c -> c[0] } }
        var diff = 0
        for (r in refTris) {
            val hit = byFirst.firstOrNull { m ->
                !used[m] && (0 until 3).any { rot -> (0 until 3).all { k -> same(r[k], mineTris[m][(k + rot) % 3]) } }
            }
            if (hit == null) diff++ else used[hit] = true
        }
        assertEquals("$name differing triangles (points ref ${ref.getInt("points")} mine ${mesh.pointCount})", 0, diff)
    }

    @Test fun avocadoKhronos() = check("Avocado_0", setOf(1))
    @Test fun avocadoStandard() = check("Avocado_eb5_0", setOf(1))
    @Test fun avocadoValence() = check("Avocado_eb0_0", setOf(1))
    @Test fun avocadoFast() = check("Avocado_eb9_0", setOf(1))
    @Test fun avocadoSequential() = check("Avocado_seq_0", setOf(1))
}
