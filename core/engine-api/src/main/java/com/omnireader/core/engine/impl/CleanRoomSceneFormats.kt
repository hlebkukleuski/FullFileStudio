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

package com.omnireader.core.engine.impl

import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

fun interface TriangleSink {
    fun triangle(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        color: Int
    ): Boolean
}

object CleanRoomSceneFormats {

    class Matrix4(val m: DoubleArray) {
        operator fun times(o: Matrix4): Matrix4 {
            val r = DoubleArray(16)
            for (i in 0 until 4) for (j in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += m[i * 4 + k] * o.m[k * 4 + j]
                r[i * 4 + j] = s
            }
            return Matrix4(r)
        }

        fun apply(x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
            out[at] = (m[0] * x + m[1] * y + m[2] * z + m[3]).toFloat()
            out[at + 1] = (m[4] * x + m[5] * y + m[6] * z + m[7]).toFloat()
            out[at + 2] = (m[8] * x + m[9] * y + m[10] * z + m[11]).toFloat()
        }

        companion object {
            val IDENTITY = Matrix4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
            fun translate(x: Double, y: Double, z: Double) = Matrix4(doubleArrayOf(1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z, 0.0, 0.0, 0.0, 1.0))
            fun scale(x: Double, y: Double, z: Double) = Matrix4(doubleArrayOf(x, 0.0, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 0.0, z, 0.0, 0.0, 0.0, 0.0, 1.0))
            fun rotate(ax: Double, ay: Double, az: Double, degrees: Double): Matrix4 {
                val len = Math.sqrt(ax * ax + ay * ay + az * az)
                if (len == 0.0) return IDENTITY
                val x = ax / len; val y = ay / len; val z = az / len
                val a = Math.toRadians(degrees)
                val c = cos(a); val s = sin(a); val t = 1 - c
                return Matrix4(
                    doubleArrayOf(
                        t * x * x + c, t * x * y - s * z, t * x * z + s * y, 0.0,
                        t * x * y + s * z, t * y * y + c, t * y * z - s * x, 0.0,
                        t * x * z - s * y, t * y * z + s * x, t * z * z + c, 0.0,
                        0.0, 0.0, 0.0, 1.0
                    )
                )
            }
        }
    }

    private fun numbers(text: String?): DoubleArray {
        if (text.isNullOrBlank()) return DoubleArray(0)
        val parts = text.trim().split(Regex("[\\s,]+"))
        val out = DoubleArray(parts.size)
        var n = 0
        for (p in parts) p.toDoubleOrNull()?.let { out[n++] = it }
        return out.copyOf(n)
    }

    private fun newParser(xml: String): XmlPullParser {
        val parser = EbookTextSession.createSafeXmlParser()
        parser.setInput(StringReader(xml))
        return parser
    }

    private fun local(parser: XmlPullParser): String = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""

    private fun attr(parser: XmlPullParser, name: String): String? {
        parser.getAttributeValue(null, name)?.let { return it }
        for (i in 0 until parser.attributeCount) {
            val n = parser.getAttributeName(i).substringAfterLast(':')
            if (n.equals(name, ignoreCase = true)) return parser.getAttributeValue(i)
        }
        return null
    }

    private class ThreeMfMesh(val vertices: FloatArray, val triangles: IntArray, val colors: IntArray)
    private class ThreeMfComponent(val objectId: String, val path: String?, val transform: Matrix4)
    private class ThreeMfObject(val mesh: ThreeMfMesh?, val components: List<ThreeMfComponent>)
    private class ThreeMfModel(val objects: Map<String, ThreeMfObject>, val build: List<ThreeMfComponent>)

    private fun parse3mfTransform(text: String?): Matrix4 {
        val n = numbers(text)
        if (n.size != 12) return Matrix4.IDENTITY
        return Matrix4(doubleArrayOf(n[0], n[3], n[6], n[9], n[1], n[4], n[7], n[10], n[2], n[5], n[8], n[11], 0.0, 0.0, 0.0, 1.0))
    }

    private fun parseHexColor(hex: String?): Int {
        if (hex.isNullOrBlank()) return 0
        val clean = hex.trim().removePrefix("#")
        return when (clean.length) {
            6 -> clean.toIntOrNull(16)?.let { (0xFF shl 24) or it } ?: 0
            8 -> clean.toLongOrNull(16)?.let { v -> (((v and 0xFF).toInt()) shl 24) or ((v ushr 8).toInt() and 0xFFFFFF) } ?: 0
            else -> 0
        }
    }

    private fun parse3mfModel(xml: String): ThreeMfModel {
        val parser = newParser(xml)
        val groups = HashMap<String, ArrayList<Int>>()
        val objects = HashMap<String, ThreeMfObject>()
        val build = ArrayList<ThreeMfComponent>()
        var group: ArrayList<Int>? = null
        var objectId: String? = null
        var objectColor = 0
        var objectPid: String? = null
        var vertices = FloatArray(3 * 1024)
        var vertexCount = 0
        var tris = IntArray(3 * 1024)
        var triColors = IntArray(1024)
        var triCount = 0
        var inMesh = false
        val components = ArrayList<ThreeMfComponent>()
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (local(parser)) {
                    "basematerials", "colorgroup" -> {
                        group = ArrayList()
                        attr(parser, "id")?.let { groups[it] = group }
                    }
                    "base" -> group?.add(parseHexColor(attr(parser, "displaycolor")))
                    "color" -> group?.add(parseHexColor(attr(parser, "color")))
                    "object" -> {
                        objectId = attr(parser, "id")
                        objectPid = attr(parser, "pid")
                        val pindex = attr(parser, "pindex")?.toIntOrNull() ?: 0
                        objectColor = objectPid?.let { groups[it]?.getOrNull(pindex) } ?: 0
                        components.clear()
                        vertexCount = 0
                        triCount = 0
                    }
                    "mesh" -> inMesh = true
                    "vertex" -> if (inMesh) {
                        if ((vertexCount + 1) * 3 > vertices.size) vertices = vertices.copyOf(vertices.size * 2)
                        vertices[vertexCount * 3] = attr(parser, "x")?.toFloatOrNull() ?: 0f
                        vertices[vertexCount * 3 + 1] = attr(parser, "y")?.toFloatOrNull() ?: 0f
                        vertices[vertexCount * 3 + 2] = attr(parser, "z")?.toFloatOrNull() ?: 0f
                        vertexCount++
                    }
                    "triangle" -> if (inMesh) {
                        if ((triCount + 1) * 3 > tris.size) {
                            tris = tris.copyOf(tris.size * 2)
                            triColors = triColors.copyOf(triColors.size * 2)
                        }
                        tris[triCount * 3] = attr(parser, "v1")?.toIntOrNull() ?: -1
                        tris[triCount * 3 + 1] = attr(parser, "v2")?.toIntOrNull() ?: -1
                        tris[triCount * 3 + 2] = attr(parser, "v3")?.toIntOrNull() ?: -1
                        val pid = attr(parser, "pid") ?: objectPid
                        val p1 = attr(parser, "p1")?.toIntOrNull()
                        triColors[triCount] = if (p1 != null && pid != null) {
                            groups[pid]?.getOrNull(p1) ?: objectColor
                        } else objectColor
                        triCount++
                    }
                    "component" -> {
                        val id = attr(parser, "objectid")
                        if (id != null) components.add(ThreeMfComponent(id, attr(parser, "path"), parse3mfTransform(attr(parser, "transform"))))
                    }
                    "item" -> {
                        val id = attr(parser, "objectid")
                        if (id != null) build.add(ThreeMfComponent(id, attr(parser, "path"), parse3mfTransform(attr(parser, "transform"))))
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (local(parser)) {
                    "basematerials", "colorgroup" -> group = null
                    "mesh" -> inMesh = false
                    "object" -> {
                        val id = objectId
                        if (id != null) {
                            val mesh = if (triCount > 0) ThreeMfMesh(vertices.copyOf(vertexCount * 3), tris.copyOf(triCount * 3), triColors.copyOf(triCount)) else null
                            objects[id] = ThreeMfObject(mesh, components.toList())
                        }
                        objectId = null
                    }
                }
            }
            ev = parser.next()
        }
        return ThreeMfModel(objects, build)
    }

    fun parse3mf(entries: Map<String, ByteArray>, rootPath: String?, sink: TriangleSink): Boolean {
        val models = HashMap<String, ThreeMfModel>()
        for ((path, xml) in entries) {
            try {
                models[normalizePath(path)] = parse3mfModel(EbookTextSession.decodeAndPreprocessXml(xml))
            } catch (_: Exception) {
            }
        }
        if (models.isEmpty()) return false
        val root = rootPath?.let { normalizePath(it) }?.takeIf { it in models } ?: models.keys.firstOrNull { it.endsWith("3dmodel.model") } ?: models.keys.first()
        val corner = FloatArray(9)
        var stopped = false
        fun emit(modelPath: String, objectId: String, transform: Matrix4, depth: Int) {
            if (stopped || depth > 32) return
            val model = models[modelPath] ?: return
            val obj = model.objects[objectId] ?: return
            obj.mesh?.let { mesh ->
                val vc = mesh.vertices.size / 3
                for (t in 0 until mesh.triangles.size / 3) {
                    val a = mesh.triangles[t * 3]; val b = mesh.triangles[t * 3 + 1]; val c = mesh.triangles[t * 3 + 2]
                    if (a !in 0 until vc || b !in 0 until vc || c !in 0 until vc) continue
                    transform.apply(mesh.vertices[a * 3], mesh.vertices[a * 3 + 1], mesh.vertices[a * 3 + 2], corner, 0)
                    transform.apply(mesh.vertices[b * 3], mesh.vertices[b * 3 + 1], mesh.vertices[b * 3 + 2], corner, 3)
                    transform.apply(mesh.vertices[c * 3], mesh.vertices[c * 3 + 1], mesh.vertices[c * 3 + 2], corner, 6)
                    if (!sink.triangle(corner[0], corner[1], corner[2], corner[3], corner[4], corner[5], corner[6], corner[7], corner[8], mesh.colors[t])) {
                        stopped = true
                        return
                    }
                }
            }
            for (comp in obj.components) {
                emit(comp.path?.let { normalizePath(it) } ?: modelPath, comp.objectId, transform * comp.transform, depth + 1)
            }
        }
        val rootModel = models.getValue(root)
        if (rootModel.build.isNotEmpty()) {
            for (item in rootModel.build) emit(item.path?.let { normalizePath(it) } ?: root, item.objectId, item.transform, 0)
        } else {
            for (id in rootModel.objects.keys) emit(root, id, Matrix4.IDENTITY, 0)
        }
        return true
    }

    private fun normalizePath(p: String): String = p.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)

    fun find3mfRootModel(rels: ByteArray?): String? {
        if (rels == null) return null
        val relsXml = EbookTextSession.decodeAndPreprocessXml(rels)
        return Regex("""Target\s*=\s*"([^"]+\.model)"""", RegexOption.IGNORE_CASE).find(relsXml)?.groupValues?.get(1)
    }

    private class DaePrimitive(val material: String?, val positionsSource: String, val positionOffset: Int, val stride: Int, val polygons: List<IntArray>)

    class ColladaResult(val upAxis: String) {
        val isYUp: Boolean get() = upAxis != "Z_UP" && upAxis != "X_UP"
    }

    fun parseCollada(bytes: ByteArray, sink: TriangleSink): ColladaResult {
        val parser = newParser(EbookTextSession.decodeAndPreprocessXml(bytes))
        val floatArrays = HashMap<String, DoubleArray>()
        val sourceArray = HashMap<String, String>()
        val verticesPosition = HashMap<String, String>()
        val geometries = HashMap<String, ArrayList<DaePrimitive>>()
        val effectColor = HashMap<String, Int>()
        val materialEffect = HashMap<String, String>()
        class Node(val transform: Matrix4) {
            val geometryInstances = ArrayList<Pair<String, Map<String, String>>>()
            val children = ArrayList<Node>()
            val nodeInstances = ArrayList<String>()
        }
        val visualScenes = HashMap<String, ArrayList<Node>>()
        val libraryNodes = HashMap<String, Node>()
        var sceneUrl: String? = null
        var upAxis = "Y_UP"

        var geometryId: String? = null
        var sourceId: String? = null
        var verticesId: String? = null
        var primitiveTag: String? = null
        var primitiveMaterial: String? = null
        var primPositionSource = ""
        var primPositionOffset = 0
        var primMaxOffset = 0
        val vcounts = ArrayList<Int>()
        val primPolygons = ArrayList<IntArray>()
        var effectId: String? = null
        var inDiffuse = false
        var materialId: String? = null
        val nodeStack = ArrayList<Node>()
        val nodeIdStack = ArrayList<String?>()
        var currentScene: ArrayList<Node>? = null
        var inLibraryNodes = false
        var pendingGeometryUrl: String? = null
        val pendingBindings = HashMap<String, String>()
        val depthTransforms = ArrayList<Matrix4>()

        fun currentNode(): Node? = nodeStack.lastOrNull()

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (local(parser)) {
                    "up_axis" -> upAxis = parser.nextText().trim().uppercase(Locale.ROOT)
                    "geometry" -> geometryId = attr(parser, "id")
                    "source" -> sourceId = attr(parser, "id")
                    "float_array" -> {
                        val id = attr(parser, "id")
                        val values = numbers(parser.nextText())
                        if (id != null) floatArrays[id] = values
                        if (sourceId != null && id != null) sourceArray[sourceId] = id
                    }
                    "vertices" -> verticesId = attr(parser, "id")
                    "triangles", "polylist", "polygons", "trifans", "tristrips" -> {
                        primitiveTag = local(parser)
                        primitiveMaterial = attr(parser, "material")
                        primPositionSource = ""
                        primPositionOffset = 0
                        primMaxOffset = 0
                        vcounts.clear()
                        primPolygons.clear()
                    }
                    "input" -> {
                        val semantic = attr(parser, "semantic")?.uppercase(Locale.ROOT) ?: ""
                        val src = attr(parser, "source")?.removePrefix("#") ?: ""
                        val offset = attr(parser, "offset")?.toIntOrNull() ?: 0
                        if (primitiveTag != null) {
                            if (offset > primMaxOffset) primMaxOffset = offset
                            if (semantic == "VERTEX") {
                                primPositionSource = verticesPosition[src] ?: src
                                primPositionOffset = offset
                            } else if (semantic == "POSITION" && primPositionSource.isEmpty()) {
                                primPositionSource = src
                                primPositionOffset = offset
                            }
                        } else if (verticesId != null && semantic == "POSITION") {
                            verticesPosition[verticesId] = src
                        }
                    }
                    "vcount" -> numbers(parser.nextText()).forEach { vcounts.add(it.toInt()) }
                    "p" -> if (primitiveTag != null) {
                        val idx = numbers(parser.nextText())
                        val stride = primMaxOffset + 1
                        val pos = IntArray(idx.size / stride) { idx[it * stride + primPositionOffset].toInt() }
                        when (primitiveTag) {
                            "triangles" -> {
                                var i = 0
                                while (i + 2 < pos.size) {
                                    primPolygons.add(intArrayOf(pos[i], pos[i + 1], pos[i + 2]))
                                    i += 3
                                }
                            }
                            "polylist" -> {
                                var cursor = 0
                                for (vc in vcounts) {
                                    if (vc < 0 || cursor + vc > pos.size) break
                                    if (vc >= 3) primPolygons.add(pos.copyOfRange(cursor, cursor + vc))
                                    cursor += vc
                                }
                            }
                            "polygons", "trifans" -> if (pos.size >= 3) primPolygons.add(pos)
                            "tristrips" -> {
                                for (i in 0 until pos.size - 2) {
                                    primPolygons.add(if (i % 2 == 0) intArrayOf(pos[i], pos[i + 1], pos[i + 2]) else intArrayOf(pos[i + 1], pos[i], pos[i + 2]))
                                }
                            }
                        }
                    }
                    "effect" -> effectId = attr(parser, "id")
                    "diffuse" -> inDiffuse = true
                    "color" -> if (inDiffuse && effectId != null) {
                        val c = numbers(parser.nextText())
                        if (c.size >= 3) {
                            fun ch(v: Double) = (v.coerceIn(0.0, 1.0) * 255).toInt()
                            effectColor[effectId] = (0xFF shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
                        }
                    }
                    "material" -> materialId = attr(parser, "id")
                    "instance_effect" -> materialId?.let { m -> attr(parser, "url")?.removePrefix("#")?.let { materialEffect[m] = it } }
                    "library_nodes" -> inLibraryNodes = true
                    "visual_scene" -> {
                        currentScene = ArrayList()
                        attr(parser, "id")?.let { visualScenes[it] = currentScene }
                    }
                    "node" -> {
                        val node = Node(Matrix4.IDENTITY)
                        val parent = currentNode()
                        if (parent != null) parent.children.add(node)
                        else if (inLibraryNodes) attr(parser, "id")?.let { libraryNodes[it] = node }
                        else currentScene?.add(node)
                        nodeStack.add(node)
                        depthTransforms.add(Matrix4.IDENTITY)
                        nodeIdStack.add(attr(parser, "id"))
                    }
                    "matrix" -> if (nodeStack.isNotEmpty()) {
                        val n = numbers(parser.nextText())
                        if (n.size == 16) depthTransforms[depthTransforms.lastIndex] = depthTransforms.last() * Matrix4(n)
                    }
                    "translate" -> if (nodeStack.isNotEmpty()) {
                        val n = numbers(parser.nextText())
                        if (n.size == 3) depthTransforms[depthTransforms.lastIndex] = depthTransforms.last() * Matrix4.translate(n[0], n[1], n[2])
                    }
                    "rotate" -> if (nodeStack.isNotEmpty()) {
                        val n = numbers(parser.nextText())
                        if (n.size == 4) depthTransforms[depthTransforms.lastIndex] = depthTransforms.last() * Matrix4.rotate(n[0], n[1], n[2], n[3])
                    }
                    "scale" -> if (nodeStack.isNotEmpty()) {
                        val n = numbers(parser.nextText())
                        if (n.size == 3) depthTransforms[depthTransforms.lastIndex] = depthTransforms.last() * Matrix4.scale(n[0], n[1], n[2])
                    }
                    "instance_geometry" -> {
                        pendingGeometryUrl = attr(parser, "url")?.removePrefix("#")
                        pendingBindings.clear()
                    }
                    "instance_material" -> {
                        val symbol = attr(parser, "symbol")
                        val target = attr(parser, "target")?.removePrefix("#")
                        if (symbol != null && target != null) pendingBindings[symbol] = target
                    }
                    "instance_node" -> currentNode()?.nodeInstances?.add(attr(parser, "url")?.removePrefix("#") ?: "")
                    "instance_visual_scene" -> sceneUrl = attr(parser, "url")?.removePrefix("#")
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (local(parser)) {
                    "geometry" -> geometryId = null
                    "source" -> sourceId = null
                    "vertices" -> verticesId = null
                    "triangles", "polylist", "polygons", "trifans", "tristrips" -> {
                        val gid = geometryId
                        if (gid != null && primPositionSource.isNotEmpty()) {
                            geometries.getOrPut(gid) { ArrayList() }.add(
                                DaePrimitive(primitiveMaterial, primPositionSource, primPositionOffset, primMaxOffset + 1, primPolygons.toList())
                            )
                        }
                        primitiveTag = null
                    }
                    "effect" -> effectId = null
                    "diffuse" -> inDiffuse = false
                    "material" -> materialId = null
                    "library_nodes" -> inLibraryNodes = false
                    "visual_scene" -> currentScene = null
                    "instance_geometry" -> {
                        val url = pendingGeometryUrl
                        if (url != null) currentNode()?.geometryInstances?.add(url to HashMap(pendingBindings))
                        pendingGeometryUrl = null
                    }
                    "node" -> {
                        val node = nodeStack.removeAt(nodeStack.lastIndex)
                        val t = depthTransforms.removeAt(depthTransforms.lastIndex)
                        nodeIdStack.removeAt(nodeIdStack.lastIndex)
                        val replaced = Node(t)
                        replaced.geometryInstances.addAll(node.geometryInstances)
                        replaced.children.addAll(node.children)
                        replaced.nodeInstances.addAll(node.nodeInstances)
                        val parent = currentNode()
                        val list = parent?.children ?: currentScene
                        if (list != null) {
                            val i = list.indexOfLast { it === node }
                            if (i >= 0) list[i] = replaced
                        }
                        libraryNodes.entries.firstOrNull { it.value === node }?.let { libraryNodes[it.key] = replaced }
                    }
                }
            }
            ev = parser.next()
        }

        val corner = FloatArray(9)
        var stopped = false
        fun positions(prim: DaePrimitive): DoubleArray? = floatArrays[sourceArray[prim.positionsSource] ?: prim.positionsSource]
        fun drawGeometry(id: String, transform: Matrix4, bindings: Map<String, String>) {
            for (prim in geometries[id].orEmpty()) {
                val pos = positions(prim) ?: continue
                val vc = pos.size / 3
                val color = prim.material?.let { sym -> (bindings[sym] ?: sym).let { mat -> materialEffect[mat]?.let { effectColor[it] } } } ?: 0
                for (poly in prim.polygons) {
                    if (stopped) return
                    if (poly.any { it !in 0 until vc }) continue
                    val a = poly[0]
                    for (k in 1 until poly.size - 1) {
                        val b = poly[k]; val c = poly[k + 1]
                        transform.apply(pos[a * 3].toFloat(), pos[a * 3 + 1].toFloat(), pos[a * 3 + 2].toFloat(), corner, 0)
                        transform.apply(pos[b * 3].toFloat(), pos[b * 3 + 1].toFloat(), pos[b * 3 + 2].toFloat(), corner, 3)
                        transform.apply(pos[c * 3].toFloat(), pos[c * 3 + 1].toFloat(), pos[c * 3 + 2].toFloat(), corner, 6)
                        if (!sink.triangle(corner[0], corner[1], corner[2], corner[3], corner[4], corner[5], corner[6], corner[7], corner[8], color)) {
                            stopped = true
                            return
                        }
                    }
                }
            }
        }
        fun drawNode(node: Node, parent: Matrix4, depth: Int) {
            if (stopped || depth > 64) return
            val t = parent * node.transform
            for ((url, bindings) in node.geometryInstances) drawGeometry(url, t, bindings)
            for (child in node.children) drawNode(child, t, depth + 1)
            for (ref in node.nodeInstances) libraryNodes[ref]?.let { drawNode(it, t, depth + 1) }
        }
        val root = if (upAxis == "X_UP") Matrix4(doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)) else Matrix4.IDENTITY
        val scene = sceneUrl?.let { visualScenes[it] } ?: visualScenes.values.firstOrNull()
        if (scene != null && scene.isNotEmpty()) {
            for (node in scene) drawNode(node, root, 0)
        } else {
            for (id in geometries.keys) drawGeometry(id, root, emptyMap())
        }
        return ColladaResult(upAxis)
    }
}
