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
 *
 * GLSL screen-space derivative tangent frame reconstruction (cotangent_frame)
 * based on Christian Schüler, "Normal Mapping Without Precomputed Tangents", ShaderX 5 (2007).
 */

package me.hlebkukleuski.fullfilestudio.core.document.mesh

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Hardware-accelerated GLES 3.0 Shader Renderer for CAD/3D meshes and vector line primitives.
 * Features:
 * - Static GPU VBO caching (`GL_ARRAY_BUFFER`, `GL_STATIC_DRAW`) for 120 FPS rotation without CPU re-upload
 * - Multi-pass rendering: opaque 3D triangles (`GL_DEPTH_TEST` with depth write), CAD wireframe lines (`GL_LINES`),
 *   and translucent glass surfaces (`glDepthMask(false)` + `glBlendFuncSeparate` preserving FBO alpha = 1.0)
 * - Camera-space 3-point studio lighting (key light + hemisphere fill + rim highlight)
 * - Dynamic near/far projection planes scaled to model bounding box (supports mm CAD and sub-meter meshes)
 */
class Gles30MeshRenderer {

    private var programId = 0
    private var mvpMatrixHandle = 0
    private var normalMatrixHandle = 0
    private var isLinePassHandle = 0

    private var triVboId = 0
    private var lineVboId = 0
    private var opaqueTriVertexCount = 0
    private var alphaTriVertexCount = 0
    private var lineVertexCount = 0

    private var uploadedSourceArray: FloatArray? = null
    private var uploadedSourceCount = -1
    private var uploadedIsLightTheme = false
    private var uploadedTextures: List<Bitmap>? = null
    private var glTextureIds = IntArray(0)
    private var textureTranslucent = BooleanArray(0)
    private var triRanges: List<DrawRange> = emptyList()
    private var useTexHandle = 0
    private var alphaCutHandle = 0
    private var samplerHandle = 0
    private var useNormalTexHandle = 0
    private var normalSamplerHandle = 0
    private var uploadedStamp = 0L
    private var uploadedNormalTextures: IntArray? = null

    private val mvpMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)

    @Volatile var isCompiled = false
        private set

    init {
        try {
            val vShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
            val fShader = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
            programId = GLES30.glCreateProgram().also {
                GLES30.glAttachShader(it, vShader)
                GLES30.glAttachShader(it, fShader)
                GLES30.glLinkProgram(it)
                val linkStatus = IntArray(1)
                GLES30.glGetProgramiv(it, GLES30.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] == 0) {
                    GLES30.glDeleteProgram(it)
                    throw IllegalStateException("GL program link failed")
                }
            }
            GLES30.glDeleteShader(vShader)
            GLES30.glDeleteShader(fShader)

            mvpMatrixHandle = GLES30.glGetUniformLocation(programId, "u_MVPMatrix")
            normalMatrixHandle = GLES30.glGetUniformLocation(programId, "u_MVMatrix")
            isLinePassHandle = GLES30.glGetUniformLocation(programId, "u_IsLinePass")
            useTexHandle = GLES30.glGetUniformLocation(programId, "u_UseTex")
            alphaCutHandle = GLES30.glGetUniformLocation(programId, "u_AlphaCut")
            samplerHandle = GLES30.glGetUniformLocation(programId, "u_Tex")
            useNormalTexHandle = GLES30.glGetUniformLocation(programId, "u_UseNormalTex")
            normalSamplerHandle = GLES30.glGetUniformLocation(programId, "u_NormalTex")

            val vbos = IntArray(2)
            GLES30.glGenBuffers(2, vbos, 0)
            triVboId = vbos[0]
            lineVboId = vbos[1]

            isCompiled = true
        } catch (_: Throwable) {
            isCompiled = false
        }
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Shader compile error: $log")
        }
        return shader
    }

    private class DrawRange(val texture: Int, val normalTexture: Int, val first: Int, val count: Int)

    private fun uploadTextures(textures: List<Bitmap>) {
        if (uploadedTextures === textures) return
        if (glTextureIds.isNotEmpty()) GLES30.glDeleteTextures(glTextureIds.size, glTextureIds, 0)
        glTextureIds = IntArray(textures.size)
        textureTranslucent = BooleanArray(textures.size)
        if (textures.isNotEmpty()) GLES30.glGenTextures(textures.size, glTextureIds, 0)
        for ((i, bmp) in textures.withIndex()) {
            if (bmp.isRecycled) continue
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glTextureIds[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            if (bmp.hasAlpha()) {
                val step = maxOf(1, (bmp.width * bmp.height) / 65536)
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                var partial = 0
                var sampled = 0
                var k = 0
                while (k < px.size) {
                    val a = px[k] ushr 24
                    if (a in 16..239) partial++
                    sampled++
                    k += step
                }
                textureTranslucent[i] = partial * 50 > sampled
            }
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        uploadedTextures = textures
    }

    private fun uploadGeometryIfNeeded(
        vertices: FloatArray,
        polyColors: IntArray,
        vertexCount: Int,
        isLightTheme: Boolean,
        vertexColors: IntArray?,
        uvs: FloatArray?,
        triTextures: IntArray?,
        textures: List<Bitmap>,
        vertexNormals: FloatArray? = null,
        triNormalTextures: IntArray? = null,
        geometryStamp: Long = 0L
    ) {
        if (uploadedSourceArray === vertices &&
            uploadedSourceCount == vertexCount &&
            uploadedIsLightTheme == isLightTheme &&
            uploadedTextures === textures &&
            uploadedStamp == geometryStamp &&
            uploadedNormalTextures === triNormalTextures
        ) {
            return
        }
        uploadTextures(textures)

        val defaultTriColor = if (isLightTheme) Color.rgb(172, 180, 194) else Color.rgb(208, 214, 224)
        val defaultWireColor = if (isLightTheme) Color.rgb(0, 121, 107) else Color.rgb(0, 230, 118)

        val totalPolys = vertexCount / 9
        val normals = FloatArray(totalPolys * 3)
        val bucket = IntArray(totalPolys)
        val texCount = textures.size
        val bucketSizes = HashMap<Int, Int>()
        var lineVerts = 0

        fun texOf(p: Int): Int {
            if (uvs == null || triTextures == null || p >= triTextures.size) return -1
            val t = triTextures[p]
            return if (t in 0 until texCount && !textures[t].isRecycled) t else -1
        }

        fun normalTexOf(p: Int): Int {
            if (uvs == null || triNormalTextures == null || p >= triNormalTextures.size) return -1
            val t = triNormalTextures[p]
            return if (t in 0 until texCount && !textures[t].isRecycled) t else -1
        }

        for (p in 0 until totalPolys) {
            val b0 = p * 9
            if (b0 + 8 >= vertices.size) {
                bucket[p] = -1
                continue
            }
            val ux = vertices[b0 + 3] - vertices[b0]
            val uy = vertices[b0 + 4] - vertices[b0 + 1]
            val uz = vertices[b0 + 5] - vertices[b0 + 2]
            val vx = vertices[b0 + 6] - vertices[b0]
            val vy = vertices[b0 + 7] - vertices[b0 + 1]
            val vz = vertices[b0 + 8] - vertices[b0 + 2]
            val crX = uy * vz - uz * vy
            val crY = uz * vx - ux * vz
            val crZ = ux * vy - uy * vx
            val len = sqrt(crX * crX + crY * crY + crZ * crZ)
            val isLine = vertices[b0 + 6] == vertices[b0] && vertices[b0 + 7] == vertices[b0 + 1] && vertices[b0 + 8] == vertices[b0 + 2]
            if (isLine) {
                bucket[p] = -1
                lineVerts += 2
                continue
            }
            if (len <= 0f) {
                bucket[p] = -2
                continue
            }
            normals[p * 3] = crX / len
            normals[p * 3 + 1] = crY / len
            normals[p * 3 + 2] = crZ / len
            val tex = texOf(p)
            var translucent = tex >= 0 && textureTranslucent[tex]
            if (!translucent) {
                val color = if (p < polyColors.size && polyColors[p] != 0) polyColors[p] else defaultTriColor
                translucent = Color.alpha(color) < 250
                if (!translucent && vertexColors != null) {
                    for (k in 0 until 3) {
                        val c = vertexColors.getOrElse(p * 3 + k) { 0 }
                        if (c != 0 && Color.alpha(c) < 250) translucent = true
                    }
                }
            }
            val b = ((tex + 1) * (texCount + 1) + (normalTexOf(p) + 1)) * 2 + (if (translucent) 1 else 0)
            bucket[p] = b
            bucketSizes[b] = (bucketSizes[b] ?: 0) + 1
        }

        val order = bucketSizes.keys.sortedWith(compareBy({ it % 2 }, { it }))
        val starts = HashMap<Int, Int>()
        var acc = 0
        for (b in order) {
            starts[b] = acc
            acc += bucketSizes[b] ?: 0
        }
        val totalTris = acc
        val ranges = ArrayList<DrawRange>()
        var opaqueTris = 0
        for (b in order) {
            val combo = b / 2
            ranges.add(DrawRange(combo / (texCount + 1) - 1, combo % (texCount + 1) - 1, (starts[b] ?: 0) * 3, (bucketSizes[b] ?: 0) * 3))
            if (b % 2 == 0) opaqueTris += bucketSizes[b] ?: 0
        }

        if (totalTris > 0 && triVboId != 0) {
            val slotPoly = IntArray(totalTris)
            val cursor = HashMap(starts)
            for (p in 0 until totalPolys) {
                val b = bucket[p]
                if (b < 0) continue
                val c = cursor[b] ?: continue
                slotPoly[c] = p
                cursor[b] = c + 1
            }
            val chunkTris = minOf(totalTris, UPLOAD_CHUNK_TRIS)
            val floats = FloatArray(chunkTris * 3 * STRIDE_FLOATS)
            val chunkBuf = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder())
            val chunkFloats = chunkBuf.asFloatBuffer()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triVboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, totalTris * 3 * STRIDE_FLOATS * 4, null, GLES30.GL_STATIC_DRAW)
            var slotStart = 0
            while (slotStart < totalTris) {
                val count = minOf(chunkTris, totalTris - slotStart)
                for (i in 0 until count) {
                    val p = slotPoly[slotStart + i]
                    val b = bucket[p]
                    val b0 = p * 9
                    val base = if (p < polyColors.size && polyColors[p] != 0) polyColors[p] else 0
                    val tex = (b / 2) / (texCount + 1) - 1
                    val hasUv = tex >= 0 || (b / 2) % (texCount + 1) > 0
                    for (k in 0 until 3) {
                        var o = (i * 3 + k) * STRIDE_FLOATS
                        val vBase = b0 + k * 3
                        floats[o++] = vertices[vBase]
                        floats[o++] = vertices[vBase + 1]
                        floats[o++] = vertices[vBase + 2]
                        val vcol = vertexColors?.getOrElse(p * 3 + k) { 0 } ?: 0
                        val color = when {
                            vcol != 0 -> vcol
                            base != 0 -> base
                            tex >= 0 -> Color.WHITE
                            else -> defaultTriColor
                        }
                        floats[o++] = Color.red(color) / 255f
                        floats[o++] = Color.green(color) / 255f
                        floats[o++] = Color.blue(color) / 255f
                        floats[o++] = Color.alpha(color) / 255f
                        val vn = vertexNormals
                        if (vn != null && (p * 3 + k) * 3 + 2 < vn.size && (vn[(p * 3 + k) * 3] != 0f || vn[(p * 3 + k) * 3 + 1] != 0f || vn[(p * 3 + k) * 3 + 2] != 0f)) {
                            var nx = vn[(p * 3 + k) * 3]
                            var ny = vn[(p * 3 + k) * 3 + 1]
                            var nz = vn[(p * 3 + k) * 3 + 2]
                            if (nx * normals[p * 3] + ny * normals[p * 3 + 1] + nz * normals[p * 3 + 2] < 0f) {
                                nx = -nx; ny = -ny; nz = -nz
                            }
                            floats[o++] = nx
                            floats[o++] = ny
                            floats[o++] = nz
                        } else {
                            floats[o++] = normals[p * 3]
                            floats[o++] = normals[p * 3 + 1]
                            floats[o++] = normals[p * 3 + 2]
                        }
                        if (hasUv) {
                            floats[o++] = uvs!![(p * 3 + k) * 2]
                            floats[o] = uvs[(p * 3 + k) * 2 + 1]
                        } else {
                            floats[o++] = 0f
                            floats[o] = 0f
                        }
                    }
                }
                val n = count * 3 * STRIDE_FLOATS
                chunkFloats.clear()
                chunkFloats.put(floats, 0, n)
                chunkBuf.position(0)
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, slotStart * 3 * STRIDE_FLOATS * 4, n * 4, chunkBuf)
                slotStart += count
            }
        }

        if (lineVerts > 0 && lineVboId != 0) {
            val lineFloats = FloatArray(lineVerts * STRIDE_FLOATS)
            var o = 0
            for (p in 0 until totalPolys) {
                if (bucket[p] != -1) continue
                val b0 = p * 9
                if (b0 + 8 >= vertices.size) continue
                val color = if (p < polyColors.size && polyColors[p] != 0) polyColors[p] else defaultWireColor
                for (k in 0 until 2) {
                    val vBase = b0 + k * 3
                    lineFloats[o++] = vertices[vBase]
                    lineFloats[o++] = vertices[vBase + 1]
                    lineFloats[o++] = vertices[vBase + 2]
                    lineFloats[o++] = Color.red(color) / 255f
                    lineFloats[o++] = Color.green(color) / 255f
                    lineFloats[o++] = Color.blue(color) / 255f
                    lineFloats[o++] = Color.alpha(color) / 255f
                    lineFloats[o++] = 0f
                    lineFloats[o++] = 1f
                    lineFloats[o++] = 0f
                    lineFloats[o++] = 0f
                    lineFloats[o++] = 0f
                }
            }
            lineVerts = o / STRIDE_FLOATS
            val lineByteBuf = ByteBuffer.allocateDirect(lineVerts * STRIDE_FLOATS * 4).order(ByteOrder.nativeOrder())
            lineByteBuf.asFloatBuffer().put(lineFloats, 0, lineVerts * STRIDE_FLOATS)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineVerts * STRIDE_FLOATS * 4, lineByteBuf, GLES30.GL_STATIC_DRAW)
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        triRanges = ranges
        opaqueTriVertexCount = opaqueTris * 3
        alphaTriVertexCount = (totalTris - opaqueTris) * 3
        lineVertexCount = lineVerts
        uploadedSourceArray = vertices
        uploadedSourceCount = vertexCount
        uploadedIsLightTheme = isLightTheme
        uploadedStamp = geometryStamp
        uploadedNormalTextures = triNormalTextures
    }

    private fun bindAttributes(vbo: Int) {
        val strideBytes = STRIDE_FLOATS * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        for (a in 0..3) GLES30.glEnableVertexAttribArray(a)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, strideBytes, 0)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, strideBytes, 3 * 4)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, strideBytes, 7 * 4)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, strideBytes, 10 * 4)
    }

    private fun unbindAttributes() {
        for (a in 0..3) GLES30.glDisableVertexAttribArray(a)
    }

    private fun drawTriangleRanges(fromVertex: Int, toVertex: Int, alphaCut: Float) {
        for (r in triRanges) {
            if (r.first < fromVertex || r.first >= toVertex) continue
            val tex = glTextureIds.getOrNull(r.texture) ?: 0
            if (r.texture >= 0 && tex != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                GLES30.glUniform1i(useTexHandle, 1)
            } else {
                GLES30.glUniform1i(useTexHandle, 0)
            }
            val ntex = glTextureIds.getOrNull(r.normalTexture) ?: 0
            if (r.normalTexture >= 0 && ntex != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ntex)
                GLES30.glUniform1i(useNormalTexHandle, 1)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            } else {
                GLES30.glUniform1i(useNormalTexHandle, 0)
            }
            GLES30.glUniform1f(alphaCutHandle, if (r.texture >= 0) alphaCut else -1f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, r.first, r.count)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun renderMesh(
        vertices: FloatArray,
        polyColors: IntArray,
        vertexCount: Int,
        vertexColors: IntArray? = null,
        uvs: FloatArray? = null,
        triTextures: IntArray? = null,
        textures: List<Bitmap> = emptyList(),
        vertexNormals: FloatArray? = null,
        triNormalTextures: IntArray? = null,
        geometryStamp: Long = 0L,
        width: Int,
        height: Int,
        yawDeg: Float,
        pitchDeg: Float,
        scale: Float,
        isYUp: Boolean,
        isLightTheme: Boolean,
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        minZ: Float, maxZ: Float
    ) {
        if (!isCompiled || vertexCount <= 0) return

        uploadGeometryIfNeeded(vertices, polyColors, vertexCount, isLightTheme, vertexColors, uvs, triTextures, textures, vertexNormals, triNormalTextures, geometryStamp)

        val bgColor = if (isLightTheme) Color.rgb(0xF5, 0xF7, 0xFA) else Color.rgb(0x12, 0x12, 0x12)
        GLES30.glClearColor(
            Color.red(bgColor) / 255f,
            Color.green(bgColor) / 255f,
            Color.blue(bgColor) / 255f,
            1.0f
        )
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        GLES30.glUseProgram(programId)
        GLES30.glUniform1i(samplerHandle, 0)
        GLES30.glUniform1i(normalSamplerHandle, 1)
        GLES30.glUniform1i(useNormalTexHandle, 0)

        MeshCamera.compute(width, height, yawDeg, pitchDeg, scale, isYUp, minX, maxX, minY, maxY, minZ, maxZ, mvMatrix, mvpMatrix)

        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(normalMatrixHandle, 1, false, mvMatrix, 0)

        if (opaqueTriVertexCount > 0 && triVboId != 0) {
            GLES30.glUniform1i(isLinePassHandle, 0)
            bindAttributes(triVboId)
            drawTriangleRanges(0, opaqueTriVertexCount, 0.5f)
            unbindAttributes()
        }

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE)

        if (lineVertexCount > 0 && lineVboId != 0) {
            GLES30.glUniform1i(isLinePassHandle, 1)
            GLES30.glUniform1i(useTexHandle, 0)
            GLES30.glUniform1f(alphaCutHandle, -1f)
            GLES30.glLineWidth(2.2f)
            bindAttributes(lineVboId)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineVertexCount)
            unbindAttributes()
        }

        if (alphaTriVertexCount > 0 && triVboId != 0) {
            GLES30.glDepthMask(false)
            GLES30.glUniform1i(isLinePassHandle, 0)
            bindAttributes(triVboId)
            drawTriangleRanges(opaqueTriVertexCount, opaqueTriVertexCount + alphaTriVertexCount, 0.01f)
            unbindAttributes()
            GLES30.glDepthMask(true)
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun destroy() {
        if (triVboId != 0 || lineVboId != 0) {
            GLES30.glDeleteBuffers(2, intArrayOf(triVboId, lineVboId), 0)
            triVboId = 0
            lineVboId = 0
        }
        if (glTextureIds.isNotEmpty()) {
            GLES30.glDeleteTextures(glTextureIds.size, glTextureIds, 0)
            glTextureIds = IntArray(0)
        }
        uploadedTextures = null
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        uploadedSourceArray = null
        uploadedSourceCount = -1
        isCompiled = false
    }

    companion object {
        private const val STRIDE_FLOATS = 12
        private const val UPLOAD_CHUNK_TRIS = 16384

        private const val VERTEX_SHADER_CODE = """#version 300 es
layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in vec3 a_Normal;
layout(location = 3) in vec2 a_UV;

uniform mat4 u_MVPMatrix;
uniform mat4 u_MVMatrix;

out vec4 v_Color;
out vec3 v_ViewNormal;
out vec2 v_UV;
out vec3 v_ViewPos;

void main() {
    v_Color = a_Color;
    v_UV = a_UV;
    v_ViewNormal = mat3(u_MVMatrix) * a_Normal;
    v_ViewPos = (u_MVMatrix * vec4(a_Position, 1.0)).xyz;
    gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);
}"""

        private const val FRAGMENT_SHADER_CODE = """#version 300 es
precision highp float;
in vec4 v_Color;
in vec3 v_ViewNormal;
in vec2 v_UV;
in vec3 v_ViewPos;
uniform int u_IsLinePass;
uniform int u_UseTex;
uniform int u_UseNormalTex;
uniform float u_AlphaCut;
uniform sampler2D u_Tex;
uniform sampler2D u_NormalTex;
out vec4 fragColor;

void main() {
    if (u_IsLinePass == 1) {
        fragColor = v_Color;
        return;
    }
    vec4 base = v_Color;
    if (u_UseTex == 1) {
        vec4 t = texture(u_Tex, v_UV);
        if (t.a > 0.0) t.rgb /= t.a;
        base *= t;
    }
    if (base.a < u_AlphaCut) discard;
    vec3 norm = normalize(v_ViewNormal);
    if (u_UseNormalTex == 1) {
        vec3 dp1 = dFdx(v_ViewPos);
        vec3 dp2 = dFdy(v_ViewPos);
        vec2 duv1 = dFdx(v_UV);
        vec2 duv2 = dFdy(v_UV);
        vec3 dp2perp = cross(dp2, norm);
        vec3 dp1perp = cross(norm, dp1);
        vec3 t = dp2perp * duv1.x + dp1perp * duv2.x;
        vec3 b = dp2perp * duv1.y + dp1perp * duv2.y;
        if (dot(norm, cross(dp1, dp2)) < 0.0) {
            t = -t;
            b = -b;
        }
        float m = max(dot(t, t), dot(b, b));
        if (m > 0.0) {
            float inv = inversesqrt(m);
            vec3 mapN = texture(u_NormalTex, v_UV).xyz * 2.0 - 1.0;
            vec3 perturbed = t * inv * mapN.x - b * inv * mapN.y + norm * mapN.z;
            if (dot(perturbed, perturbed) > 0.0) norm = normalize(perturbed);
        }
    }
    vec3 keyDir = normalize(vec3(0.42, 0.64, 0.64));
    float keyDot = abs(dot(norm, keyDir));
    float hemiFill = 0.5 + 0.5 * norm.y;
    float rim = clamp(1.0 - abs(norm.z), 0.0, 1.0);
    float spec = pow(keyDot, 4.0) * 0.22;
    float intensity = clamp(0.34 + 0.48 * keyDot + 0.18 * hemiFill + 0.12 * rim * rim, 0.28, 1.15);
    vec3 rgb = base.rgb * intensity + vec3(spec);
    fragColor = vec4(clamp(rgb, 0.0, 1.0), base.a);
}"""
    }
}
