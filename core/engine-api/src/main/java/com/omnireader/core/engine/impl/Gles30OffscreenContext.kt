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

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offscreen EGL14 + GLES 3.0 Context manager providing pixel-level 24-bit Z-buffering
 * (`GL_DEPTH_COMPONENT24`) and reused direct pixel buffers for 120 FPS 0-OOM rendering.
 */
class Gles30OffscreenContext(
    val width: Int,
    val height: Int
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var fboId = 0
    private var rboColorId = 0
    private var rboDepthId = 0
    private var cachedPixelByteBuffer: ByteBuffer? = null

    @Volatile var isInitialized = false
        private set

    init {
        try {
            initEgl()
            initFbo()
            isInitialized = true
        } catch (_: Throwable) {
            destroy()
            isInitialized = false
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("EGLDisplay unavailable")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("EGL14.eglInitialize failed")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x0040, // EGL_OPENGL_ES3_BIT
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw IllegalStateException("eglChooseConfig failed")
        }

        val contextAttribs = intArrayOf(
            0x3098, 3, // EGL_CONTEXT_CLIENT_VERSION = 3
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext failed")
        }

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width.coerceAtLeast(16),
            EGL14.EGL_HEIGHT, height.coerceAtLeast(16),
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreatePbufferSurface failed")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
    }

    private fun initFbo() {
        val buffers = IntArray(3)
        GLES30.glGenFramebuffers(1, buffers, 0)
        fboId = buffers[0]

        GLES30.glGenRenderbuffers(2, buffers, 1)
        rboColorId = buffers[1]
        rboDepthId = buffers[2]

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)

        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rboColorId)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, width, height)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rboColorId)

        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rboDepthId)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, rboDepthId)

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("FBO status incomplete: 0x${Integer.toHexString(status)}")
        }

        GLES30.glViewport(0, 0, width, height)
    }

    fun makeCurrent() {
        if (!isInitialized) return
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, width, height)
    }

    fun readPixelsToBitmap(bitmap: Bitmap) {
        if (!isInitialized || bitmap.isRecycled) return
        makeCurrent()
        val neededBytes = width * height * 4
        var buf = cachedPixelByteBuffer
        if (buf == null || buf.capacity() < neededBytes) {
            buf = ByteBuffer.allocateDirect(neededBytes).order(ByteOrder.LITTLE_ENDIAN)
            cachedPixelByteBuffer = buf
        } else {
            buf.clear()
        }
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        bitmap.copyPixelsFromBuffer(buf)
    }

    fun destroy() {
        if (fboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (rboColorId != 0 || rboDepthId != 0) {
            GLES30.glDeleteRenderbuffers(2, intArrayOf(rboColorId, rboDepthId), 0)
            rboColorId = 0
            rboDepthId = 0
        }
        cachedPixelByteBuffer = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        isInitialized = false
    }
}
