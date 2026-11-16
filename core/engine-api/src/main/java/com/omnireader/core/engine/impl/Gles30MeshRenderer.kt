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

import android.graphics.Color
import android.opengl.GLES30
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

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
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

    private fun uploadGeometryIfNeeded(
        vertices: FloatArray,
        polyColors: IntArray,
        vertexCount: Int,
        isLightTheme: Boolean
    ) {
        if (uploadedSourceArray === vertices &&
            uploadedSourceCount == vertexCount &&
            uploadedIsLightTheme == isLightTheme
        ) {
            return
        }

        // Neutral Studio Clay / CAD Cool Silver-Gray so uncolored meshes (STL, OBJ without MTL, PLY, FBX, BLEND, 3DS)
        // render like a professional 3D studio viewport rather than neon green.
        val defaultTriColor = if (isLightTheme) Color.rgb(172, 180, 194) else Color.rgb(208, 214, 224)
        val defaultWireColor = if (isLightTheme) Color.rgb(0, 121, 107) else Color.rgb(0, 230, 118)

        val totalPolys = vertexCount / 9
        var opaqueVerts = 0
        var alphaVerts = 0
        var lineVerts = 0

        for (p in 0 until totalPolys) {
            val b0 = p * 9
            if (b0 + 8 >= vertices.size) break
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
            if (len <= 1e-6f) {
                lineVerts += 2
            } else {
                val color = if (p < polyColors.size && polyColors[p] != 0) polyColors[p] else defaultTriColor
                if (Color.alpha(color) < 250) {
                    alphaVerts += 3
                } else {
                    opaqueVerts += 3
                }
            }
        }

        val totalTriVerts = opaqueVerts + alphaVerts
        if (totalTriVerts > 0 && triVboId != 0) {
            val triByteBuf = ByteBuffer.allocateDirect(totalTriVerts * 10 * 4).order(ByteOrder.nativeOrder())
            val triFloatBuf = triByteBuf.asFloatBuffer()

            // Pack opaque triangles first [0 .. opaqueVerts), then translucent triangles [opaqueVerts .. totalTriVerts)
            for (passAlpha in booleanArrayOf(false, true)) {
                if (!passAlpha && opaqueVerts == 0) continue
                if (passAlpha && alphaVerts == 0) continue

                for (p in 0 until totalPolys) {
                    val b0 = p * 9
                    if (b0 + 8 >= vertices.size) break
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
                    if (len <= 1e-6f) continue

                    val color = if (p < polyColors.size && polyColors[p] != 0) polyColors[p] else defaultTriColor
                    val isTranslucent = Color.alpha(color) < 250
                    if (isTranslucent != passAlpha) continue

                    val nx = crX / len
                    val ny = crY / len
                    val nz = crZ / len

                    val r = Color.red(color) / 255f
                    val g = Color.green(color) / 255f
                    val b = Color.blue(color) / 255f
                    val a = Color.alpha(color) / 255f

                    for (k in 0 until 3) {
                        val vBase = b0 + k * 3
                        triFloatBuf.put(vertices[vBase])
                        triFloatBuf.put(vertices[vBase + 1])
                        triFloatBuf.put(vertices[vBase + 2])
                        triFloatBuf.put(r); triFloatBuf.put(g); triFloatBuf.put(b); triFloatBuf.put(a)
                        triFloatBuf.put(nx); triFloatBuf.put(ny); triFloatBuf.put(nz)
                    }
                }
            }
            triFloatBuf.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triVboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, totalTriVerts * 10 * 4, triFloatBuf, GLES30.GL_STATIC_DRAW)
        }

        if (lineVerts > 0 && lineVboId != 0) {
            val lineByteBuf = ByteBuffer.allocateDirect(lineVerts * 10 * 4).order(ByteOrder.nativeOrder())
            val lineFloatBuf = lineByteBuf.asFloatBuffer()
            for (p in 0 until totalPolys) {
                val b0 = p * 9
                if (b0 + 8 >= vertices.size) break
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
                if (len > 1e-6f) continue

                val color = if (p < polyColors.size && polyColors[p] != 0) polyColors[p] else defaultWireColor
                val r = Color.red(color) / 255f
                val g = Color.green(color) / 255f
                val b = Color.blue(color) / 255f
                val a = Color.alpha(color) / 255f

                for (k in 0 until 2) {
                    val vBase = b0 + k * 3
                    lineFloatBuf.put(vertices[vBase])
                    lineFloatBuf.put(vertices[vBase + 1])
                    lineFloatBuf.put(vertices[vBase + 2])
                    lineFloatBuf.put(r); lineFloatBuf.put(g); lineFloatBuf.put(b); lineFloatBuf.put(a)
                    lineFloatBuf.put(0f); lineFloatBuf.put(1f); lineFloatBuf.put(0f)
                }
            }
            lineFloatBuf.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVboId)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineVerts * 10 * 4, lineFloatBuf, GLES30.GL_STATIC_DRAW)
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        opaqueTriVertexCount = opaqueVerts
        alphaTriVertexCount = alphaVerts
        lineVertexCount = lineVerts
        uploadedSourceArray = vertices
        uploadedSourceCount = vertexCount
        uploadedIsLightTheme = isLightTheme
    }

    fun renderMesh(
        vertices: FloatArray,
        polyColors: IntArray,
        vertexCount: Int,
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

        uploadGeometryIfNeeded(vertices, polyColors, vertexCount, isLightTheme)

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

        val rangeX = (maxX - minX).let { if (it.isFinite() && it > 0f) it else 1f }
        val rangeY = (maxY - minY).let { if (it.isFinite() && it > 0f) it else 1f }
        val rangeZ = (maxZ - minZ).let { if (it.isFinite() && it > 0f) it else 1f }
        val maxDim = maxOf(rangeX, rangeY, rangeZ).coerceAtLeast(1e-3f)

        val eyeDist = (maxDim * 2.25f) / scale.coerceAtLeast(0.05f)
        val nearPlane = (maxDim * 0.01f).coerceAtLeast(0.001f)
        val farPlane = (eyeDist + maxDim * 12f).coerceAtLeast(100f)

        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, nearPlane, farPlane)
        // Flip Y so GLES FBO readPixels aligns directly with Android Bitmap top-left origin
        Matrix.scaleM(projectionMatrix, 0, 1f, -1f, 1f)

        val cx = if (minX.isFinite()) minX + rangeX / 2f else 0f
        val cy = if (minY.isFinite()) minY + rangeY / 2f else 0f
        val cz = if (minZ.isFinite()) minZ + rangeZ / 2f else 0f

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, eyeDist, 0f, 0f, 0f, 0f, 1f, 0f)

        val isFlat2dPlan = rangeZ < maxOf(rangeX, rangeY) * 0.005f

        Matrix.setIdentityM(modelMatrix, 0)
        // Compensate vertical pitch sign for the Y-inverted FBO projection matrix
        Matrix.rotateM(modelMatrix, 0, -pitchDeg, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, yawDeg, 0f, 1f, 0f)
        if (!isYUp && !isFlat2dPlan) {
            Matrix.rotateM(modelMatrix, 0, -90f, 1f, 0f, 0f)
        }
        Matrix.translateM(modelMatrix, 0, -cx, -cy, -cz)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(normalMatrixHandle, 1, false, mvMatrix, 0)

        val strideBytes = 10 * 4

        // Pass 1: Opaque triangles with full Z-buffer write
        if (opaqueTriVertexCount > 0 && triVboId != 0) {
            GLES30.glUniform1i(isLinePassHandle, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triVboId)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, strideBytes, 0)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, strideBytes, 3 * 4)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, strideBytes, 7 * 4)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, opaqueTriVertexCount)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDisableVertexAttribArray(2)
        }

        // Enable alpha blending while preserving FBO destination alpha = 1.0 for Android premultiplied Bitmap
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE)

        // Pass 2: CAD wireframe lines
        if (lineVertexCount > 0 && lineVboId != 0) {
            GLES30.glUniform1i(isLinePassHandle, 1)
            GLES30.glLineWidth(2.2f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVboId)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, strideBytes, 0)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, strideBytes, 3 * 4)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, strideBytes, 7 * 4)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineVertexCount)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDisableVertexAttribArray(2)
        }

        // Pass 3: Translucent glass / windows (depth-tested against opaque geometry, depth-write disabled)
        if (alphaTriVertexCount > 0 && triVboId != 0) {
            GLES30.glDepthMask(false)
            GLES30.glUniform1i(isLinePassHandle, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triVboId)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, strideBytes, 0)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, strideBytes, 3 * 4)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, strideBytes, 7 * 4)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, opaqueTriVertexCount, alphaTriVertexCount)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDisableVertexAttribArray(2)
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
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        uploadedSourceArray = null
        uploadedSourceCount = -1
        isCompiled = false
    }

    companion object {
        private const val VERTEX_SHADER_CODE = """#version 300 es
layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in vec3 a_Normal;

uniform mat4 u_MVPMatrix;
uniform mat4 u_MVMatrix;

out vec4 v_Color;
out vec3 v_ViewNormal;

void main() {
    v_Color = a_Color;
    v_ViewNormal = mat3(u_MVMatrix) * a_Normal;
    gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);
}"""

        private const val FRAGMENT_SHADER_CODE = """#version 300 es
precision mediump float;
in vec4 v_Color;
in vec3 v_ViewNormal;
uniform int u_IsLinePass;
out vec4 fragColor;

void main() {
    if (u_IsLinePass == 1) {
        fragColor = v_Color;
        return;
    }
    vec3 norm = normalize(v_ViewNormal);
    vec3 keyDir = normalize(vec3(0.42, 0.64, 0.64));
    float keyDot = abs(dot(norm, keyDir));
    float hemiFill = 0.5 + 0.5 * norm.y;
    float rim = clamp(1.0 - abs(norm.z), 0.0, 1.0);
    float spec = pow(keyDot, 4.0) * 0.22;
    float intensity = clamp(0.34 + 0.48 * keyDot + 0.18 * hemiFill + 0.12 * rim * rim, 0.28, 1.15);
    vec3 rgb = v_Color.rgb * intensity + vec3(spec);
    fragColor = vec4(clamp(rgb, 0.0, 1.0), v_Color.a);
}"""
    }
}
