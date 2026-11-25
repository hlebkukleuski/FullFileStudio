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
import android.os.ParcelFileDescriptor
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic fuzz and adversarial input safety suite for 3D CAD and vector geometry parsers
 * (Draco, Meshopt, STEP AP203/AP214, IGES, USD/USDC/USDA, glTF 2.0, DXF, SVG).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CadParserFuzzSafetyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class SinkGltfOutput : GltfOutput {
        override val truncated: Boolean = false
        var triangleCount = 0
        override fun triangle(p: FloatArray, color: Int, uv: FloatArray?, texture: Int, c0: Int, c1: Int, c2: Int) {
            triangleCount++
        }
        override fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, color: Int) {}
        override fun texture(key: String, bytes: ByteArray?, opaque: Boolean): Int = -1
        override fun resource(uri: String): ByteArray? = null
    }

    @Test(timeout = 15_000L)
    fun dracoAndMeshoptDecodersSurviveAdversarialBitstreams() {
        val rng = Random(0xCAD3D001L)
        assertTrue(DracoDecoder.decode(ByteArray(0)) == null)

        val modes = arrayOf("ATTRIBUTES", "TRIANGLES", "INDICES")
        val filters = arrayOf("NONE", "OCTAHEDRAL", "QUATERNION", "EXPONENTIAL")
        val strides = intArrayOf(4, 8, 12, 16)

        repeat(250) { idx ->
            val raw = rng.nextBytes(rng.nextInt(1, 320))
            if (idx % 2 == 0 && raw.size >= 5) {
                "DRACO".toByteArray(StandardCharsets.US_ASCII).copyInto(raw, 0)
            }
            val mesh = DracoDecoder.decode(raw)
            if (mesh != null) {
                assertTrue(mesh.pointCount >= 0)
            }

            val stride = strides[idx % strides.size]
            val count = rng.nextInt(0, 64)
            try {
                MeshoptDecoder.decode(
                    src = raw,
                    count = count,
                    stride = stride,
                    mode = modes[idx % modes.size],
                    filter = filters[idx % filters.size]
                )
            } catch (_: Exception) {}
        }
    }

    @Test(timeout = 15_000L)
    fun stepAndIgesParsersSurviveCyclicEntityGraphsAndTruncatedSyntax() {
        val rng = Random(0xCAD3D002L)
        val cyclicStep = """
            ISO-10303-21;
            HEADER;ENDSEC;
            DATA;
            #1=CARTESIAN_POINT('',(0.0,0.0,0.0));
            #2=VERTEX_POINT('',#1);
            #3=EDGE_CURVE('',#2,#2,#3,.T.);
            #4=ORIENTED_EDGE('',*,*,#3,.T.);
            #5=EDGE_LOOP('',(#4,#5));
            #6=FACE_OUTER_BOUND('',#5,.T.);
            #7=ADVANCED_FACE('',(#6),#7,.T.);
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        val entities = StepIsoLexer.parse(cyclicStep)
        assertNotNull(entities)
        val sink = SinkGltfOutput()
        StepParser(entities, sink).load()

        repeat(120) { idx ->
            val noise = String(rng.nextBytes(rng.nextInt(16, 512)), StandardCharsets.ISO_8859_1)
            val stepInput = if (idx % 2 == 0) "DATA;\n#1=FACETED_BREP($noise);\nENDSEC;" else noise
            val parsedStep = StepIsoLexer.parse(stepInput)
            try {
                StepParser(parsedStep, SinkGltfOutput()).load()
            } catch (_: Exception) {}

            val igesInput = buildString {
                append("124,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0;             1P      1\n")
                append("     124       1       0       0       0       0       1       000000001D      1\n")
                append("     124       0       0       1       0                               0D      2\n")
                append(noise.take(80))
            }
            val parsedIges = IgesSectionLexer.parse(igesInput)
            try {
                IgesParser(parsedIges, SinkGltfOutput()).load()
            } catch (_: Exception) {}
        }
    }

    @Test(timeout = 15_000L)
    fun usdCrateAsciiAndGltfParsersSurviveMalformedContainers() {
        val rng = Random(0xCAD3D003L)

        repeat(120) { idx ->
            val buf = rng.nextBytes(rng.nextInt(16, 1024))
            if (idx % 3 == 0 && buf.size >= 88) {
                "PXR-USDC".toByteArray(StandardCharsets.US_ASCII).copyInto(buf, 0)
            }
            val stage = UsdStage(
                fetchFn = { path -> if (path == "root.usdc" || path == "root.usda") buf else null },
                rootId = if (idx % 3 == 0) "root.usdc" else "root.usda"
            )
            assertNotNull(stage)

            val gltfJson = if (idx % 2 == 0) {
                """
                {
                  "asset": { "version": "2.0" },
                  "nodes": [
                    { "children": [1], "mesh": 0 },
                    { "children": [0], "mesh": 0 }
                  ],
                  "meshes": [{ "primitives": [{ "attributes": { "POSITION": 0 }, "indices": 1 }] }],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 999999, "type": "VEC3" },
                    { "bufferView": 0, "componentType": 5123, "count": -5, "type": "SCALAR" }
                  ],
                  "bufferViews": [{ "buffer": 0, "byteOffset": -10, "byteLength": 9999999 }]
                }
                """.trimIndent()
            } else {
                "{\"raw\": ${JSONObject.quote(String(buf, StandardCharsets.UTF_8))}}"
            }

            try {
                GltfParser(
                    root = JSONObject(gltfJson),
                    glbBin = { offset, length ->
                        if (offset < 0L || length < 0 || offset + length > buf.size) {
                            null
                        } else {
                            buf.copyOfRange(offset.toInt(), (offset + length).toInt())
                        }
                    },
                    glbBinLength = buf.size.toLong(),
                    out = SinkGltfOutput(),
                    maxElements = 10_000
                ).load()
            } catch (_: Exception) {}
        }
    }

    @Test(timeout = 15_000L)
    fun cadVectorEngineFactorySafelyHandlesCorruptedFormatsViaFileDescriptor() {
        val rng = Random(0xCAD3D004L)
        val factory = CadVectorEngineFactory()
        val extensions = listOf("stl", "obj", "dxf", "svg", "svgz", "gltf", "glb", "usdz", "step", "iges")
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)

        for (ext in extensions) {
            val f = tempFolder.newFile("corrupt.$ext")
            f.writeBytes(rng.nextBytes(256))
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val session = factory.createSessionWithPassword(pfd.fileDescriptor, ext, null)
                try {
                    session.renderTile(0, 0, 0, 1000, bitmap)
                    assertTrue(session.pageCount >= 1)
                } finally {
                    session.close()
                }
            }
        }

        val malformedDxf = "0\nSECTION\n2\nENTITIES\n0\nSPLINE\n71\n99\n0\nENDSEC\n0\nEOF\n"
        val dxfFile = tempFolder.newFile("malformed.dxf")
        dxfFile.writeBytes(malformedDxf.toByteArray(StandardCharsets.US_ASCII))
        ParcelFileDescriptor.open(dxfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val session = factory.createSessionWithPassword(pfd.fileDescriptor, "dxf", null)
            try {
                session.renderTile(0, 25, -20, 1000, bitmap)
            } finally {
                session.close()
            }
        }
        bitmap.recycle()
    }
}
