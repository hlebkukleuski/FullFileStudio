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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CadFormatsTest {

    private class AreaSink : GltfOutput {
        var area = 0.0
        var count = 0
        var maxDistFromAxis = 0.0
        val colors = HashSet<Int>()
        override val truncated = false
        override fun triangle(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int) {
            val a = Vec3(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())
            val b = Vec3(p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
            val c = Vec3(p[6].toDouble(), p[7].toDouble(), p[8].toDouble())
            area += (b - a).cross(c - a).len() / 2
            count++
            colors.add(color)
            for (v in listOf(a, b, c)) maxDistFromAxis = maxOf(maxDistFromAxis, kotlin.math.sqrt(v.x * v.x + v.y * v.y))
        }
        override fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int) {}
        override fun texture(key: String, bytes: ByteArray?, opaque: Boolean) = -1
        override fun resource(uri: String): ByteArray? = null
    }

    private val cylinderStep = """
ISO-10303-21;
HEADER;
FILE_DESCRIPTION((''),'2;1');
ENDSEC;
DATA;
#1=CARTESIAN_POINT('',(0.,0.,0.));
#2=DIRECTION('',(0.,0.,1.));
#3=DIRECTION('',(1.,0.,0.));
#4=AXIS2_PLACEMENT_3D('',#1,#2,#3);
#5=CYLINDRICAL_SURFACE('',#4,1.);
#6=CARTESIAN_POINT('',(0.,0.,2.));
#7=AXIS2_PLACEMENT_3D('',#6,#2,#3);
#8=CIRCLE('',#4,1.);
#9=CIRCLE('',#7,1.);
#10=CARTESIAN_POINT('',(1.,0.,0.));
#11=CARTESIAN_POINT('',(1.,0.,2.));
#12=VERTEX_POINT('',#10);
#13=VERTEX_POINT('',#11);
#14=EDGE_CURVE('',#12,#12,#8,.T.);
#15=EDGE_CURVE('',#13,#13,#9,.T.);
#16=VECTOR('',#2,1.);
#17=LINE('',#10,#16);
#18=EDGE_CURVE('',#12,#13,#17,.T.);
#19=ORIENTED_EDGE('',*,*,#14,.T.);
#20=ORIENTED_EDGE('',*,*,#18,.T.);
#21=ORIENTED_EDGE('',*,*,#15,.F.);
#22=ORIENTED_EDGE('',*,*,#18,.F.);
#23=EDGE_LOOP('',(#19,#20,#21,#22));
#24=FACE_OUTER_BOUND('',#23,.T.);
#25=ADVANCED_FACE('',(#24),#5,.T.);
#26=PLANE('',#4);
#27=PLANE('',#7);
#28=ORIENTED_EDGE('',*,*,#14,.F.);
#29=EDGE_LOOP('',(#28));
#30=FACE_OUTER_BOUND('',#29,.T.);
#31=ADVANCED_FACE('',(#30),#26,.F.);
#32=ORIENTED_EDGE('',*,*,#15,.T.);
#33=EDGE_LOOP('',(#32));
#34=FACE_OUTER_BOUND('',#33,.T.);
#35=ADVANCED_FACE('',(#34),#27,.T.);
#36=CLOSED_SHELL('',(#25,#31,#35));
#37=MANIFOLD_SOLID_BREP('',#36);
#38=COLOUR_RGB('',1.,0.,0.);
#39=FILL_AREA_STYLE_COLOUR('',#38);
#40=FILL_AREA_STYLE('',(#39));
#41=SURFACE_STYLE_FILL_AREA(#40);
#42=SURFACE_SIDE_STYLE('',(#41));
#43=SURFACE_STYLE_USAGE(.BOTH.,#42);
#44=PRESENTATION_STYLE_ASSIGNMENT((#43));
#45=STYLED_ITEM('',(#44),#37);
#46=ADVANCED_BREP_SHAPE_REPRESENTATION('',(#37),#99);
ENDSEC;
END-ISO-10303-21;
""".trimIndent()

    @Test
    fun stepCylinderArea() {
        val sink = AreaSink()
        val ok = StepParser(StepParser.parse(cylinderStep), sink).load()
        assertTrue(ok)
        val expected = 2 * Math.PI * 1.0 * 2.0 + 2 * Math.PI
        assertEquals(expected, sink.area, expected * 0.01)
        assertTrue(sink.maxDistFromAxis < 1.0001)
        assertEquals(setOf(0xFFFF0000.toInt()), sink.colors)
    }

    private fun igesLine(data: String, section: Char, seq: Int) = data.padEnd(72) + section + seq.toString().padStart(7)

    @Test
    fun igesTrimmedBilinearPatch() {
        val lines = ArrayList<String>()
        lines.add(igesLine("", 'S', 1))
        lines.add(igesLine("1H,,1H;;", 'G', 1))
        val p128 = "128,1,1,1,1,0,0,1,0,0,0.,0.,1.,1.,0.,0.,1.,1.,1.,1.,1.,1.,0.,0.,0.,2.,0.,0.,0.,3.,0.,2.,3.,0.,0.,1.,0.,1.;"
        val p126 = "126,3,1,0,0,1,0,0.,0.,1.,2.,3.,3.,1.,1.,1.,1.,0.,0.,0.,1.,0.,0.,0.,1.,0.,0.,0.,0.,0.,3.,0.,0.,1.;"
        val p142 = "142,0,1,3,0,1;"
        val p144 = "144,1,1,0,5;"
        fun chunks(s: String) = s.chunked(64)
        val params = listOf(p128 to 1, p126 to 3, p142 to 5, p144 to 7)
        var pSeq = 1
        val dLines = ArrayList<String>()
        val pLines = ArrayList<String>()
        for ((text, de) in params) {
            val start = pSeq
            for (c in chunks(text)) {
                pLines.add(c.padEnd(64) + " " + de.toString().padStart(7) + "P" + pSeq.toString().padStart(7))
                pSeq++
            }
            val type = text.substringBefore(',').padStart(8)
            val status = (if (de == 7) "00000000" else "00010000")
            dLines.add(igesLine(type + start.toString().padStart(8) + "       0       0       0       0       0       0" + status, 'D', de))
            dLines.add(igesLine(type + "       0       0       " + (pSeq - start) + "       0", 'D', de + 1))
        }
        lines.addAll(dLines)
        lines.addAll(pLines)
        val sink = AreaSink()
        val ok = IgesParser(IgesParser.parse(lines.joinToString("\n")), sink).load()
        assertTrue(ok)
        assertEquals(6.0 * 0.5, sink.area, 0.03)
    }
}
