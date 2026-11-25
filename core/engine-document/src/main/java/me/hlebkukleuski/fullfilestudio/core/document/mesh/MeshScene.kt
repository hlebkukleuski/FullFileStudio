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

package me.hlebkukleuski.fullfilestudio.core.document.mesh

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MeshLabel(
    val text: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val height: Float,
    val rotationDeg: Float,
    val color: Int
)

class MeshScene(
    val vertices: FloatArray,
    val vertexCount: Int,
    val polyColors: IntArray,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
    val isYUp: Boolean,
    val modelType: String,
    val isTruncated: Boolean,
    val maxPolygons: Int,
    val labels: List<MeshLabel>,
    val preview: Bitmap?,
    val vertexColors: IntArray? = null,
    val uvs: FloatArray? = null,
    val triTextures: IntArray? = null,
    val textures: List<Bitmap> = emptyList(),
    val vertexNormals: FloatArray? = null,
    val triNormalTextures: IntArray? = null,
    val animation: MeshAnimation? = null
) {
    val polygonCount: Int get() = vertexCount / 9

    fun posed(time: Float, clip: Int = 0): MeshScene {
        val anim = animation ?: return this
        if (time < 0f) return this
        val pose = anim.pose(time, vertices, vertexCount, vertexNormals, clip)
        return MeshScene(
            pose.vertices, vertexCount, polyColors, minX, maxX, minY, maxY, minZ, maxZ, isYUp, modelType, isTruncated, maxPolygons,
            labels, preview, vertexColors, uvs, triTextures, textures, pose.normals ?: vertexNormals, triNormalTextures, animation
        )
    }

    @Volatile private var framingCache: MeshScene? = null

    fun framed(): MeshScene {
        val anim = animation ?: return this
        framingCache?.let { return it }
        val b = anim.bounds(vertices, vertexCount)
        val f = if (b[0] <= b[1]) MeshScene(
            vertices, vertexCount, polyColors,
            minOf(minX, b[0]), maxOf(maxX, b[1]), minOf(minY, b[2]), maxOf(maxY, b[3]), minOf(minZ, b[4]), maxOf(maxZ, b[5]),
            isYUp, modelType, isTruncated, maxPolygons, labels, preview, vertexColors, uvs, triTextures, textures, vertexNormals, triNormalTextures, animation
        ) else this
        framingCache = f
        return f
    }
    val hasSurfaceDetail: Boolean get() = vertexColors != null || (uvs != null && triTextures != null && textures.isNotEmpty())
}

interface MeshSceneSource {
    fun exportMeshScene(): MeshScene?
}

object MeshSceneCodec {

    private const val MAGIC = 0x4D534646
    private const val VERSION = 3

    private fun utf8(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    fun encodedSize(scene: MeshScene): Int {
        var size = 4 * 4 + 4 * 4 + 6 * 4
        size += 4 + utf8(scene.modelType).size
        size += 4
        for (l in scene.labels) size += 4 + utf8(l.text).size + 6 * 4 + 4
        size += 8
        val p = scene.preview
        if (p != null && !p.isRecycled) size += p.width * p.height * 4
        size += scene.vertexCount * 4
        size += scene.polygonCount * 4
        val corners = scene.polygonCount * 3
        if (scene.vertexColors != null) size += corners * 4
        if (scene.uvs != null && scene.triTextures != null) size += corners * 2 * 4 + scene.polygonCount * 4
        if (scene.vertexNormals != null) size += corners * 3 * 4
        if (scene.triNormalTextures != null) size += scene.polygonCount * 4
        scene.animation?.let { size += it.encodedSize() }
        size += 4
        for (t in scene.textures) size += 8 + (if (t.isRecycled) 0 else t.width * t.height * 4)
        return size
    }

    private fun putBitmap(out: ByteBuffer, bmp: Bitmap?) {
        val p = bmp?.takeIf { !it.isRecycled }
        out.putInt(p?.width ?: 0)
        out.putInt(p?.height ?: 0)
        if (p != null) {
            val px = IntArray(p.width * p.height)
            p.getPixels(px, 0, p.width, 0, 0, p.width, p.height)
            out.asIntBuffer().put(px)
            out.position(out.position() + px.size * 4)
        }
    }

    private fun getBitmap(b: ByteBuffer): Bitmap? {
        val w = b.int
        val h = b.int
        require(w in 0..8192 && h in 0..8192 && w.toLong() * h * 4 <= b.remaining()) { "Corrupted mesh scene bitmap" }
        if (w == 0 || h == 0) return null
        val px = IntArray(w * h)
        b.asIntBuffer().get(px)
        b.position(b.position() + px.size * 4)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun encode(scene: MeshScene, out: ByteBuffer) {
        out.order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC)
        out.putInt(VERSION)
        val hasUv = scene.uvs != null && scene.triTextures != null
        out.putInt((if (scene.isYUp) 1 else 0) or (if (scene.isTruncated) 2 else 0) or
            (if (scene.vertexColors != null) 4 else 0) or (if (hasUv) 8 else 0) or (if (scene.vertexNormals != null) 16 else 0) or
            (if (scene.triNormalTextures != null && hasUv) 32 else 0) or (if (scene.animation != null) 64 else 0))
        out.putInt(scene.maxPolygons)
        out.putInt(scene.vertexCount)
        out.putInt(scene.polygonCount)
        out.putInt(scene.labels.size)
        out.putInt(0)
        out.putFloat(scene.minX); out.putFloat(scene.maxX)
        out.putFloat(scene.minY); out.putFloat(scene.maxY)
        out.putFloat(scene.minZ); out.putFloat(scene.maxZ)
        val type = utf8(scene.modelType)
        out.putInt(type.size); out.put(type)
        out.putInt(scene.labels.size)
        for (l in scene.labels) {
            val t = utf8(l.text)
            out.putInt(t.size); out.put(t)
            out.putFloat(l.x); out.putFloat(l.y); out.putFloat(l.z)
            out.putFloat(l.height); out.putFloat(l.rotationDeg); out.putFloat(0f)
            out.putInt(l.color)
        }
        putBitmap(out, scene.preview)
        out.asFloatBuffer().put(scene.vertices, 0, scene.vertexCount)
        out.position(out.position() + scene.vertexCount * 4)
        out.asIntBuffer().put(scene.polyColors, 0, scene.polygonCount)
        out.position(out.position() + scene.polygonCount * 4)
        val corners = scene.polygonCount * 3
        scene.vertexColors?.let {
            out.asIntBuffer().put(it, 0, corners)
            out.position(out.position() + corners * 4)
        }
        if (hasUv) {
            out.asFloatBuffer().put(scene.uvs, 0, corners * 2)
            out.position(out.position() + corners * 8)
            out.asIntBuffer().put(scene.triTextures, 0, scene.polygonCount)
            out.position(out.position() + scene.polygonCount * 4)
        }
        scene.vertexNormals?.let {
            out.asFloatBuffer().put(it, 0, corners * 3)
            out.position(out.position() + corners * 12)
        }
        if (scene.triNormalTextures != null && hasUv) {
            out.asIntBuffer().put(scene.triNormalTextures, 0, scene.polygonCount)
            out.position(out.position() + scene.polygonCount * 4)
        }
        scene.animation?.encode(out)
        out.putInt(scene.textures.size)
        for (t in scene.textures) putBitmap(out, t)
    }

    fun decode(input: ByteBuffer): MeshScene {
        val b = input.order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == MAGIC) { "Bad mesh scene magic" }
        require(b.int == VERSION) { "Unsupported mesh scene version" }
        val flags = b.int
        val maxPolygons = b.int
        val vertexCount = b.int
        val polygonCount = b.int
        b.int
        b.int
        require(vertexCount in 0..Int.MAX_VALUE / 4 && polygonCount == vertexCount / 9) { "Corrupted mesh scene" }
        val minX = b.float; val maxX = b.float
        val minY = b.float; val maxY = b.float
        val minZ = b.float; val maxZ = b.float
        fun str(): String {
            val n = b.int
            require(n in 0..b.remaining()) { "Corrupted mesh scene string" }
            val arr = ByteArray(n)
            b.get(arr)
            return String(arr, StandardCharsets.UTF_8)
        }
        val modelType = str()
        val labelCount = b.int
        require(labelCount in 0..100_000) { "Corrupted mesh scene labels" }
        val labels = ArrayList<MeshLabel>(labelCount)
        repeat(labelCount) {
            val t = str()
            val x = b.float; val y = b.float; val z = b.float
            val h = b.float; val r = b.float
            b.float
            labels.add(MeshLabel(t, x, y, z, h, r, b.int))
        }
        val preview = getBitmap(b)
        require(vertexCount.toLong() * 4 + polygonCount.toLong() * 4 <= b.remaining()) { "Corrupted mesh scene geometry" }
        val vertices = FloatArray(vertexCount)
        b.asFloatBuffer().get(vertices)
        b.position(b.position() + vertexCount * 4)
        val colors = IntArray(polygonCount)
        b.asIntBuffer().get(colors)
        b.position(b.position() + polygonCount * 4)
        val corners = polygonCount * 3
        val vertexColors = if (flags and 4 != 0) {
            require(corners.toLong() * 4 <= b.remaining()) { "Corrupted mesh scene colors" }
            IntArray(corners).also {
                b.asIntBuffer().get(it)
                b.position(b.position() + corners * 4)
            }
        } else null
        var uvs: FloatArray? = null
        var triTextures: IntArray? = null
        if (flags and 8 != 0) {
            require(corners.toLong() * 8 + polygonCount.toLong() * 4 <= b.remaining()) { "Corrupted mesh scene uvs" }
            uvs = FloatArray(corners * 2).also {
                b.asFloatBuffer().get(it)
                b.position(b.position() + corners * 8)
            }
            triTextures = IntArray(polygonCount).also {
                b.asIntBuffer().get(it)
                b.position(b.position() + polygonCount * 4)
            }
        }
        val normals = if (flags and 16 != 0) {
            require(corners.toLong() * 12 <= b.remaining()) { "Corrupted mesh scene normals" }
            FloatArray(corners * 3).also {
                b.asFloatBuffer().get(it)
                b.position(b.position() + corners * 12)
            }
        } else null
        val normalTextures = if (flags and 32 != 0) {
            require(polygonCount.toLong() * 4 <= b.remaining()) { "Corrupted mesh scene normal maps" }
            IntArray(polygonCount).also {
                b.asIntBuffer().get(it)
                b.position(b.position() + polygonCount * 4)
            }
        } else null
        val animation = if (flags and 64 != 0) MeshAnimation.decode(b, polygonCount) else null
        val texCount = b.int
        require(texCount in 0..4096) { "Corrupted mesh scene textures" }
        val textures = ArrayList<Bitmap>(texCount)
        repeat(texCount) { textures.add(getBitmap(b) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }) }
        return MeshScene(
            vertices, vertexCount, colors, minX, maxX, minY, maxY, minZ, maxZ,
            isYUp = flags and 1 != 0,
            modelType = modelType,
            isTruncated = flags and 2 != 0,
            maxPolygons = maxPolygons,
            labels = labels,
            preview = preview,
            vertexColors = vertexColors,
            uvs = uvs,
            triTextures = triTextures,
            textures = textures,
            vertexNormals = normals,
            triNormalTextures = normalTextures,
            animation = animation
        )
    }
}

class MeshSceneRenderer {

    companion object {
        const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
        private const val LANG_ORDINAL_SHIFT = 24
        private val DARK_BG = Color.rgb(0x12, 0x12, 0x12)
        private val LIGHT_BG = Color.rgb(0xF5, 0xF7, 0xFA)
        private val DARK_HUD = Color.WHITE
        private val LIGHT_HUD = Color.rgb(0x1A, 0x1C, 0x24)
        private val DARK_LABEL = Color.rgb(0x89, 0xB4, 0xFA)
        private val LIGHT_LABEL = Color.rgb(0x19, 0x76, 0xD2)
        private const val CPU_FALLBACK_MAX_TRIANGLES = 300_000
        const val CAP_HEIGHT_RATIO = 0.711f
        private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SANS_SERIF
            textSize = 100f
        }

        fun measureLabelWidth(text: String, height: Double): Double {
            if (text.isEmpty() || !height.isFinite() || height <= 0) return 0.0
            val w = synchronized(measurePaint) { measurePaint.measureText(text) }
            return w / 100.0 * height / CAP_HEIGHT_RATIO
        }

        fun formatHudStatus(langOrdinal: Int, modelType: String, polyCount: Int, isTruncated: Boolean, maxLimit: Int): String {
            val localizedModelType = when (modelType) {
                "Binary STL" -> when (langOrdinal) {
                    1 -> "Бинарный STL"
                    2 -> "Binäres STL"
                    3 -> "STL binario"
                    4 -> "STL binaire"
                    5 -> "Binarny STL"
                    6 -> "Бінарний STL"
                    7 -> "二进制 STL"
                    else -> "Binary STL"
                }
                else -> modelType
            }
            val polygonsWord = when (langOrdinal) {
                1 -> "Полигоны"
                2 -> "Polygone"
                3 -> "Polígonos"
                4 -> "Polygones"
                5 -> "Wielokąty"
                6 -> "Полігони"
                7 -> "多边形"
                else -> "Polygons"
            }
            val maxWord = when (langOrdinal) {
                1 -> "Макс."
                2 -> "Max."
                3 -> "Máx."
                4 -> "Max."
                5 -> "Maks."
                6 -> "Макс."
                7 -> "最大"
                else -> "Max"
            }
            val limitBadge = if (isTruncated) " | ⚠ $maxWord $maxLimit" else ""
            return "3D/CAD: $localizedModelType | $polygonsWord: $polyCount$limitBadge"
        }
    }

    private var glesContext: Gles30OffscreenContext? = null
    private var glesRenderer: Gles30MeshRenderer? = null
    private var glesUnavailable = false
    private val softwareRasterizer = me.hlebkukleuski.fullfilestudio.core.document.mesh.SoftwareMeshRasterizer()

    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.SANS_SERIF
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.SANS_SERIF
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    @Volatile var animationTime: Float = -1f
    @Volatile var animationClip: Int = 0

    fun render(original: MeshScene, pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        val time = animationTime
        val source = original.framed()
        val scene = if (time >= 0f && source.animation != null) source.posed(time, animationClip) else source
        val stamp = if (scene !== source) (java.lang.Float.floatToRawIntBits(time).toLong() shl 16) + animationClip + 1L else 0L
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0 || (pageIndex and 0xFF) == 1
        val langOrdinal = (pageIndex ushr LANG_ORDINAL_SHIFT) and 0xF
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val zoom = (zoomPermille / 1000f).coerceAtLeast(0.1f)

        val preview = scene.preview
        if (preview != null && !preview.isRecycled && scene.vertexCount == 0) {
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (isLight) LIGHT_BG else DARK_BG)
            val aspect = preview.width.toFloat() / preview.height.toFloat().coerceAtLeast(1f)
            val availW = (width - 80f) * zoom
            val availH = (height - 120f) * zoom
            val dstW: Float
            val dstH: Float
            if (availW / availH.coerceAtLeast(1f) > aspect) {
                dstH = availH
                dstW = dstH * aspect
            } else {
                dstW = availW
                dstH = dstW / aspect
            }
            val left = (width - dstW) / 2f + (tileX - 25) * 4f
            val top = (height - dstH) / 2f - (tileY + 20) * 4f
            canvas.drawBitmap(preview, null, RectF(left, top, left + dstW, top + dstH), bitmapPaint)
            drawHud(canvas, scene, height, isLight, langOrdinal, "${scene.modelType} (Preview ${preview.width}×${preview.height})", 0)
            return
        }

        if (scene.vertexCount > 0 && renderWithGles(scene, bitmap, tileX, tileY, zoom, isLight, stamp)) {
            val canvas = Canvas(bitmap)
            drawLabels(canvas, scene, bitmap.width, bitmap.height, tileX, tileY, zoom, isLight)
            drawHud(canvas, scene, height, isLight, langOrdinal, scene.modelType, scene.polygonCount)
            return
        }

        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLight) LIGHT_BG else DARK_BG)
        if (scene.vertexCount > 0 || scene.labels.isNotEmpty()) {
            if (scene.vertexCount > 0) {
                try {
                    softwareRasterizer.render(scene, bitmap, tileX.toFloat(), tileY.toFloat(), zoom, isLight, CPU_FALLBACK_MAX_TRIANGLES)
                } catch (_: Throwable) {}
            }
            drawLabels(canvas, scene, bitmap.width, bitmap.height, tileX, tileY, zoom, isLight)
            drawHud(canvas, scene, height, isLight, langOrdinal, scene.modelType, scene.polygonCount)
        }
    }

    private fun renderWithGles(scene: MeshScene, bitmap: Bitmap, tileX: Int, tileY: Int, zoom: Float, isLight: Boolean, stamp: Long): Boolean {
        if (glesUnavailable) return false
        return try {
            var ctx = glesContext
            if (ctx != null && (ctx.width != bitmap.width || ctx.height != bitmap.height)) {
                ctx.makeCurrent()
                glesRenderer?.destroy()
                glesRenderer = null
                ctx.destroy()
                glesContext = null
                ctx = null
            }
            if (ctx == null && bitmap.width > 0 && bitmap.height > 0) {
                ctx = Gles30OffscreenContext(bitmap.width, bitmap.height)
                if (ctx.isInitialized) {
                    glesContext = ctx
                    ctx.makeCurrent()
                    glesRenderer = Gles30MeshRenderer()
                } else {
                    ctx.destroy()
                    ctx = null
                    glesUnavailable = true
                }
            }
            val renderer = glesRenderer
            if (ctx == null || !ctx.isInitialized || renderer == null || !renderer.isCompiled) return false
            if (!ctx.makeCurrent()) return false
            renderer.renderMesh(
                vertices = scene.vertices,
                polyColors = scene.polyColors,
                vertexCount = scene.vertexCount,
                vertexColors = scene.vertexColors,
                uvs = scene.uvs,
                triTextures = scene.triTextures,
                textures = scene.textures,
                vertexNormals = scene.vertexNormals,
                triNormalTextures = scene.triNormalTextures,
                geometryStamp = stamp,
                width = bitmap.width,
                height = bitmap.height,
                yawDeg = tileX.toFloat(),
                pitchDeg = tileY.toFloat(),
                scale = zoom,
                isYUp = scene.isYUp,
                isLightTheme = isLight,
                minX = scene.minX, maxX = scene.maxX,
                minY = scene.minY, maxY = scene.maxY,
                minZ = scene.minZ, maxZ = scene.maxZ
            )
            ctx.readPixelsToBitmap(bitmap)
            true
        } catch (_: Throwable) {
            false
        } finally {
            try { glesContext?.releaseCurrent() } catch (_: Throwable) {}
        }
    }

    private fun drawLabels(canvas: Canvas, scene: MeshScene, w: Int, h: Int, tileX: Int, tileY: Int, zoom: Float, isLight: Boolean) {
        if (scene.labels.isEmpty()) return
        val defaultColor = if (isLight) LIGHT_LABEL else DARK_LABEL
        val mv = FloatArray(16)
        val mvp = FloatArray(16)
        me.hlebkukleuski.fullfilestudio.core.document.mesh.MeshCamera.compute(
            w, h, tileX.toFloat(), tileY.toFloat(), zoom, scene.isYUp, scene.minX, scene.maxX, scene.minY, scene.maxY, scene.minZ, scene.maxZ, mv, mvp
        )
        val upLen = sqrt(mv[1] * mv[1] + mv[5] * mv[5] + mv[9] * mv[9]).takeIf { it > 0f } ?: 1f
        val ux = mv[1] / upLen / upLen
        val uy = mv[5] / upLen / upLen
        val uz = mv[9] / upLen / upLen
        fun screen(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
            val cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
            if (cw <= 1e-6f) return false
            out[0] = ((mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]) / cw + 1f) * 0.5f * w
            out[1] = ((mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]) / cw + 1f) * 0.5f * h
            return true
        }
        val a = FloatArray(2)
        val b = FloatArray(2)
        for (lbl in scene.labels) {
            if (!screen(lbl.x, lbl.y, lbl.z, a)) continue
            val hh = lbl.height / CAP_HEIGHT_RATIO
            if (!screen(lbl.x + ux * hh, lbl.y + uy * hh, lbl.z + uz * hh, b)) continue
            val screenX = a[0].coerceIn(-w * 2f, w * 3f)
            val screenY = a[1].coerceIn(-h * 2f, h * 3f)
            val size = kotlin.math.hypot(b[0] - a[0], b[1] - a[1])
            if (!size.isFinite() || size < 1.5f) continue
            labelPaint.textSize = size.coerceAtMost(4000f)
            labelPaint.color = if (lbl.color != 0) lbl.color else defaultColor
            canvas.save()
            canvas.rotate(-lbl.rotationDeg, screenX, screenY)
            canvas.drawText(lbl.text, screenX, screenY, labelPaint)
            canvas.restore()
        }
    }

    private fun drawHud(canvas: Canvas, scene: MeshScene, height: Float, isLight: Boolean, langOrdinal: Int, modelType: String, polyCount: Int) {
        hudPaint.color = if (isLight) LIGHT_HUD else DARK_HUD
        hudPaint.textSize = 26f
        canvas.drawText(
            formatHudStatus(langOrdinal, modelType, polyCount, scene.isTruncated && polyCount > 0, scene.maxPolygons),
            30f, height - 32f, hudPaint
        )
    }

    fun release() {
        try { glesContext?.makeCurrent() } catch (_: Throwable) { }
        try { glesRenderer?.destroy() } catch (_: Throwable) { }
        glesRenderer = null
        try { glesContext?.destroy() } catch (_: Throwable) { }
        glesContext = null
    }
}
