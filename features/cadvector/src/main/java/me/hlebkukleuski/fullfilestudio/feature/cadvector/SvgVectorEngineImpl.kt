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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.graphics.PathParser
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.FileEngineProvider
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.ZeroCacheReadableSource
import com.omnireader.core.engine.impl.EbookTextSession
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Stack
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.tan

class SvgVectorEngineImpl : FileEngineProvider {
    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        return ext == "svg" || ext == "svgz"
    }

    override fun createSession(fd: FileDescriptor): DocumentSession {
        val pfd = ParcelFileDescriptor.dup(fd)
        return SvgSession(pfd)
    }
}

class SvgSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null

    data class GradientStop(val offset: Float, val color: Int)

    sealed class SvgGradient(val id: String, val isUserSpaceOnUse: Boolean) {
        class Linear(
            id: String,
            isUserSpaceOnUse: Boolean,
            val x1: Float, val y1: Float,
            val x2: Float, val y2: Float,
            val stops: MutableList<GradientStop> = mutableListOf()
        ) : SvgGradient(id, isUserSpaceOnUse)

        class Radial(
            id: String,
            isUserSpaceOnUse: Boolean,
            val cx: Float, val cy: Float, val r: Float,
            val stops: MutableList<GradientStop> = mutableListOf()
        ) : SvgGradient(id, isUserSpaceOnUse)
    }

    data class ClipPathDefinition(
        val id: String,
        val objectBoundingBoxUnits: Boolean,
        val combinedPath: Path = Path()
    )

    data class MaskDefinition(
        val id: String,
        val shapes: MutableList<SvgShape> = mutableListOf()
    )

    data class SvgShape(
        val path: Path,
        val fillColor: Int?,
        val fillGradientId: String?,
        val strokeColor: Int?,
        val strokeGradientId: String?,
        val strokeWidth: Float,
        val strokeCap: Paint.Cap,
        val strokeJoin: Paint.Join,
        val activeClipIds: List<String> = emptyList(),
        val activeMaskId: String? = null
    )

    data class SvgGroupTemplate(
        val id: String,
        val viewBox: RectF? = null,
        val shapes: MutableList<SvgShape> = mutableListOf()
    )

    data class SvgEmbeddedImage(
        val bitmap: Bitmap,
        val dstRect: RectF,
        val transform: Matrix,
        val opacity: Float,
        val activeClipIds: List<String> = emptyList(),
        val activeMaskId: String? = null
    )

    data class SvgTextElement(
        val text: String,
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val fillColor: Int?,
        val textAlign: Paint.Align,
        val transform: Matrix,
        val activeClipIds: List<String> = emptyList()
    )

    data class SvgStyle(
        var fillColor: Int? = Color.BLACK,
        var fillGradientId: String? = null,
        var strokeColor: Int? = null,
        var strokeGradientId: String? = null,
        var strokeWidth: Float = 1f,
        var opacity: Float = 1f,
        var fillOpacity: Float = 1f,
        var strokeOpacity: Float = 1f,
        var fontSize: Float = 16f,
        var textAnchor: Paint.Align = Paint.Align.LEFT,
        var fillRule: Path.FillType = Path.FillType.WINDING,
        var strokeCap: Paint.Cap = Paint.Cap.BUTT,
        var strokeJoin: Paint.Join = Paint.Join.MITER,
        var clipPathIds: List<String> = emptyList(),
        var maskId: String? = null
    )

    private val shapes = mutableListOf<SvgShape>()
    private val embeddedImages = mutableListOf<SvgEmbeddedImage>()
    private val texts = mutableListOf<SvgTextElement>()
    private val gradients = mutableMapOf<String, SvgGradient>()
    private val clipPaths = mutableMapOf<String, ClipPathDefinition>()
    private val masks = mutableMapOf<String, MaskDefinition>()
    private val cssRules = mutableMapOf<String, Map<String, String>>()
    private val defsPaths = mutableMapOf<String, Path>()
    private val defsGroups = mutableMapOf<String, SvgGroupTemplate>()

    private val bounds = RectF()
    private var currentViewBox: RectF? = null

    private val matrixStack = Stack<Matrix>()
    private val styleStack = Stack<SvgStyle>()
    private val groupTemplateStack = Stack<SvgGroupTemplate?>()

    private var activeTransform = Matrix()
    private var activeStyle = SvgStyle()

    companion object {
        private const val MAX_SVG_UNCOMPRESSED_BYTES = 32 * 1024 * 1024
        private const val MAX_INLINE_IMAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_SVG_SHAPES = 50_000
    }

    init {
        parseSvgDocument()
    }

    override fun getLastError(): String? = lastErrorMsg

    override fun getDocumentWidth(): Float {
        val vb = currentViewBox ?: bounds
        return if (!vb.isEmpty) vb.width() else 1000f
    }

    override fun getDocumentHeight(): Float {
        val vb = currentViewBox ?: bounds
        return if (!vb.isEmpty) vb.height() else 1000f
    }

    private fun readAndDecompressSvgBytes(): ByteArray? {
        val source = ZeroCacheReadableSource(pfd.fileDescriptor)
        val rawSize = minOf(source.size, MAX_SVG_UNCOMPRESSED_BYTES.toLong()).toInt()
        if (rawSize <= 0) {
            lastErrorMsg = "Файл SVG/SVGZ пуст (0 байт)."
            return null
        }
        val direct = ByteBuffer.allocateDirect(rawSize)
        val read = source.readAt(0L, direct)
        if (read <= 0) {
            lastErrorMsg = "Не удалось прочитать данные SVG/SVGZ."
            return null
        }
        val rawBytes = ByteArray(read)
        direct.flip()
        direct.get(rawBytes)

        val isGzip = read >= 2 && (rawBytes[0].toInt() and 0xFF) == 0x1F && (rawBytes[1].toInt() and 0xFF) == 0x8B
        if (!isGzip) return rawBytes

        return try {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gis ->
                val bos = ByteArrayOutputStream(minOf(read * 3, MAX_SVG_UNCOMPRESSED_BYTES))
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val r = gis.read(buf)
                    if (r == -1) break
                    total += r
                    if (total > MAX_SVG_UNCOMPRESSED_BYTES) {
                        throw SecurityException("Превышен безопасный лимит декомпрессии SVGZ (32 МБ).")
                    }
                    bos.write(buf, 0, r)
                }
                bos.toByteArray()
            }
        } catch (e: Exception) {
            lastErrorMsg = "Повреждённый архив SVGZ (ошибка декомпрессии GZIP): ${e.localizedMessage}"
            null
        }
    }

    private fun parseGradientCoord(raw: String?, defaultVal: Float, isUserSpace: Boolean): Float {
        if (raw.isNullOrBlank()) return defaultVal
        val trimmed = raw.trim()
        if (trimmed.endsWith("%")) {
            val pct = trimmed.removeSuffix("%").trim().toFloatOrNull() ?: return defaultVal
            if (!pct.isFinite()) return defaultVal
            return if (isUserSpace) pct else pct / 100f
        }
        return parseCoord(trimmed, defaultVal)
    }

    private fun parseViewBoxAttr(parser: XmlPullParser): RectF? {
        val vb = parser.getAttributeValue(null, "viewBox")
            ?: parser.getAttributeValue(null, "viewbox")
            ?: return null
        val parts = vb.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (parts.size >= 4 && parts[2] > 0f && parts[3] > 0f) {
            return RectF(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
        }
        return null
    }

    private fun parseSvgDocument() {
        val xmlBytes = readAndDecompressSvgBytes() ?: return

        try {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
                try {
                    setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                } catch (_: Throwable) {}
            }
            val parser = factory.newPullParser()
            val charset = try {
                EbookTextSession.detectXmlCharset(xmlBytes)
            } catch (_: Throwable) {
                StandardCharsets.UTF_8
            }
            val startOffset = if (charset == StandardCharsets.UTF_8 &&
                xmlBytes.size >= 3 &&
                xmlBytes[0] == 0xEF.toByte() &&
                xmlBytes[1] == 0xBB.toByte() &&
                xmlBytes[2] == 0xBF.toByte()
            ) 3 else 0

            var blockedImageError: String? = null

            InputStreamReader(ByteArrayInputStream(xmlBytes, startOffset, xmlBytes.size - startOffset), charset).use { reader ->
                parser.setInput(reader)
                var eventType = parser.eventType

                var inDefs = 0
                var inStyleTag = false
                var sawSvgRoot = false
                var activeGradient: SvgGradient? = null
                var activeClipPathDef: ClipPathDefinition? = null
                var activeMaskDef: MaskDefinition? = null
                var activeTextBuilder: StringBuilder? = null
                var activeTextX = 0f
                var activeTextY = 0f
                var activeTextStyle = SvgStyle()
                var activeTextMatrix = Matrix()

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            val name = parser.name?.lowercase(Locale.ROOT) ?: ""
                            val idAttr = parser.getAttributeValue(null, "id")?.trim()

                            when (name) {
                                "defs" -> inDefs++
                                "symbol" -> {
                                    inDefs++
                                    matrixStack.push(Matrix(activeTransform))
                                    styleStack.push(activeStyle.copy())
                                    activeStyle = resolveElementStyle(parser, activeStyle)
                                    val tmpl = if (!idAttr.isNullOrEmpty()) {
                                        SvgGroupTemplate(idAttr, parseViewBoxAttr(parser)).also { defsGroups[idAttr] = it }
                                    } else null
                                    groupTemplateStack.push(tmpl)
                                }
                                "clippath" -> {
                                    inDefs++
                                    if (!idAttr.isNullOrEmpty()) {
                                        val units = parser.getAttributeValue(null, "clipPathUnits")?.trim()
                                        val isObb = units.equals("objectBoundingBox", ignoreCase = true)
                                        val def = ClipPathDefinition(idAttr, isObb)
                                        clipPaths[idAttr] = def
                                        activeClipPathDef = def
                                    }
                                }
                                "mask" -> {
                                    inDefs++
                                    if (!idAttr.isNullOrEmpty()) {
                                        val def = MaskDefinition(idAttr)
                                        masks[idAttr] = def
                                        activeMaskDef = def
                                    }
                                }
                                "style" -> inStyleTag = true
                                "lineargradient" -> {
                                    val gid = idAttr ?: ""
                                    val units = parser.getAttributeValue(null, "gradientUnits")?.trim()
                                    val isUserSpace = units.equals("userSpaceOnUse", ignoreCase = true)
                                    val defX2 = if (isUserSpace) 100f else 1f
                                    val x1 = parseGradientCoord(parser.getAttributeValue(null, "x1"), 0f, isUserSpace)
                                    val y1 = parseGradientCoord(parser.getAttributeValue(null, "y1"), 0f, isUserSpace)
                                    val x2 = parseGradientCoord(parser.getAttributeValue(null, "x2"), defX2, isUserSpace)
                                    val y2 = parseGradientCoord(parser.getAttributeValue(null, "y2"), 0f, isUserSpace)
                                    val grad = SvgGradient.Linear(gid, isUserSpace, x1, y1, x2, y2)
                                    if (gid.isNotEmpty()) gradients[gid] = grad
                                    activeGradient = grad
                                }
                                "radialgradient" -> {
                                    val gid = idAttr ?: ""
                                    val units = parser.getAttributeValue(null, "gradientUnits")?.trim()
                                    val isUserSpace = units.equals("userSpaceOnUse", ignoreCase = true)
                                    val defCenter = if (isUserSpace) 50f else 0.5f
                                    val cx = parseGradientCoord(parser.getAttributeValue(null, "cx"), defCenter, isUserSpace)
                                    val cy = parseGradientCoord(parser.getAttributeValue(null, "cy"), defCenter, isUserSpace)
                                    val r = parseGradientCoord(parser.getAttributeValue(null, "r"), defCenter, isUserSpace)
                                    val grad = SvgGradient.Radial(gid, isUserSpace, cx, cy, r)
                                    if (gid.isNotEmpty()) gradients[gid] = grad
                                    activeGradient = grad
                                }
                                "stop" -> {
                                    val grad = activeGradient
                                    if (grad != null) {
                                        val offsetRaw = parser.getAttributeValue(null, "offset") ?: "0"
                                        val offset = if (offsetRaw.endsWith("%")) {
                                            (offsetRaw.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
                                        } else {
                                            offsetRaw.toFloatOrNull() ?: 0f
                                        }.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }

                                        var stopColorStr = parser.getAttributeValue(null, "stop-color")
                                        var stopOpacityStr = parser.getAttributeValue(null, "stop-opacity")
                                        val styleAttr = parser.getAttributeValue(null, "style")
                                        if (styleAttr != null) {
                                            for (prop in styleAttr.split(";")) {
                                                val kv = prop.split(":")
                                                if (kv.size == 2) {
                                                    when (kv[0].trim().lowercase(Locale.ROOT)) {
                                                        "stop-color" -> stopColorStr = kv[1].trim()
                                                        "stop-opacity" -> stopOpacityStr = kv[1].trim()
                                                    }
                                                }
                                            }
                                        }
                                        val baseColor = parseSvgColor(stopColorStr) ?: Color.BLACK
                                        val stopAlpha = (stopOpacityStr?.toFloatOrNull() ?: 1f).let {
                                            if (it.isFinite()) it.coerceIn(0f, 1f) else 1f
                                        }
                                        val finalColor = applyAlpha(baseColor, stopAlpha)

                                        when (grad) {
                                            is SvgGradient.Linear -> grad.stops.add(GradientStop(offset, finalColor))
                                            is SvgGradient.Radial -> grad.stops.add(GradientStop(offset, finalColor))
                                        }
                                    }
                                }
                                "svg" -> {
                                    sawSvgRoot = true
                                    val vbRect = parseViewBoxAttr(parser)
                                    if (vbRect != null) {
                                        currentViewBox = vbRect
                                    } else {
                                        val w = parseCoord(parser.getAttributeValue(null, "width"), 0f)
                                        val h = parseCoord(parser.getAttributeValue(null, "height"), 0f)
                                        if (w > 0f && h > 0f) {
                                            currentViewBox = RectF(0f, 0f, w, h)
                                        }
                                    }
                                }
                                "g" -> {
                                    matrixStack.push(Matrix(activeTransform))
                                    styleStack.push(activeStyle.copy())
                                    activeStyle = resolveElementStyle(parser, activeStyle)
                                    applyTransform(activeTransform, parser.getAttributeValue(null, "transform"))
                                    val tmpl = if (!idAttr.isNullOrEmpty()) {
                                        SvgGroupTemplate(idAttr).also { defsGroups[idAttr] = it }
                                    } else null
                                    groupTemplateStack.push(tmpl)
                                }
                                "image" -> {
                                    if (inDefs == 0) {
                                        val imgErr = parseEmbeddedImageElement(parser)
                                        if (imgErr != null) blockedImageError = imgErr
                                    }
                                }
                                "text" -> {
                                    activeTextStyle = resolveElementStyle(parser, activeStyle)
                                    activeTextX = parseCoord(parser.getAttributeValue(null, "x"), 0f)
                                    activeTextY = parseCoord(parser.getAttributeValue(null, "y"), 0f)
                                    activeTextMatrix = Matrix(activeTransform)
                                    applyTransform(activeTextMatrix, parser.getAttributeValue(null, "transform"))
                                    activeTextBuilder = StringBuilder()
                                }
                                "tspan" -> {
                                    val tx = parser.getAttributeValue(null, "x")?.let { parseCoord(it, activeTextX) }
                                    val ty = parser.getAttributeValue(null, "y")?.let { parseCoord(it, activeTextY) }
                                    if (tx != null) activeTextX = tx
                                    if (ty != null) activeTextY = ty
                                }
                                "use" -> {
                                    if (shapes.size < MAX_SVG_SHAPES) {
                                        handleUseElement(parser, inDefs)
                                    }
                                }
                                else -> {
                                    if (shapes.size < MAX_SVG_SHAPES) {
                                        val elemStyle = resolveElementStyle(parser, activeStyle)
                                        val path = buildShapePath(name, parser, elemStyle)
                                        if (path != null) {
                                            path.fillType = elemStyle.fillRule
                                            if (!idAttr.isNullOrEmpty()) {
                                                defsPaths[idAttr] = Path(path)
                                            }

                                            val fillCol = elemStyle.fillColor?.let {
                                                applyAlpha(it, elemStyle.opacity * elemStyle.fillOpacity)
                                            }
                                            val strokeCol = elemStyle.strokeColor?.let {
                                                applyAlpha(it, elemStyle.opacity * elemStyle.strokeOpacity)
                                            }

                                            val clipDef = activeClipPathDef
                                            if (clipDef != null) {
                                                val clipLocalMatrix = Matrix()
                                                applyTransform(clipLocalMatrix, parser.getAttributeValue(null, "transform"))
                                                val copyForClip = Path(path)
                                                copyForClip.transform(clipLocalMatrix)
                                                clipDef.combinedPath.addPath(copyForClip)
                                            } else if (activeMaskDef != null) {
                                                val maskLocalMatrix = Matrix()
                                                applyTransform(maskLocalMatrix, parser.getAttributeValue(null, "transform"))
                                                val copyForMask = Path(path)
                                                copyForMask.transform(maskLocalMatrix)
                                                activeMaskDef?.shapes?.add(
                                                    SvgShape(
                                                        path = copyForMask,
                                                        fillColor = fillCol,
                                                        fillGradientId = elemStyle.fillGradientId,
                                                        strokeColor = elemStyle.strokeColor,
                                                        strokeGradientId = elemStyle.strokeGradientId,
                                                        strokeWidth = elemStyle.strokeWidth,
                                                        strokeCap = elemStyle.strokeCap,
                                                        strokeJoin = elemStyle.strokeJoin
                                                    )
                                                )
                                            } else {
                                                val localMatrix = Matrix(activeTransform)
                                                applyTransform(localMatrix, parser.getAttributeValue(null, "transform"))
                                                path.transform(localMatrix)

                                                val createdShape = SvgShape(
                                                    path = path,
                                                    fillColor = fillCol,
                                                    fillGradientId = elemStyle.fillGradientId,
                                                    strokeColor = strokeCol,
                                                    strokeGradientId = elemStyle.strokeGradientId,
                                                    strokeWidth = elemStyle.strokeWidth,
                                                    strokeCap = elemStyle.strokeCap,
                                                    strokeJoin = elemStyle.strokeJoin,
                                                    activeClipIds = elemStyle.clipPathIds,
                                                    activeMaskId = elemStyle.maskId
                                                )

                                                for (grp in groupTemplateStack) {
                                                    if (grp != null && grp.shapes.size < 2000) {
                                                        grp.shapes.add(createdShape.copy(path = Path(path)))
                                                    }
                                                }

                                                if (inDefs == 0) {
                                                    shapes.add(createdShape)
                                                    val pathBounds = RectF()
                                                    path.computeBounds(pathBounds, true)
                                                    if (!pathBounds.isEmpty) {
                                                        if (bounds.isEmpty) bounds.set(pathBounds) else bounds.union(pathBounds)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        XmlPullParser.TEXT -> {
                            val txt = parser.text
                            if (inStyleTag && !txt.isNullOrBlank()) {
                                parseCssBlock(txt)
                            } else if (activeTextBuilder != null && !txt.isNullOrBlank()) {
                                activeTextBuilder.append(txt.trim()).append(" ")
                            }
                        }

                        XmlPullParser.END_TAG -> {
                            val name = parser.name?.lowercase(Locale.ROOT) ?: ""
                            when (name) {
                                "defs" -> inDefs = (inDefs - 1).coerceAtLeast(0)
                                "symbol" -> {
                                    inDefs = (inDefs - 1).coerceAtLeast(0)
                                    if (matrixStack.isNotEmpty()) activeTransform = matrixStack.pop()
                                    if (styleStack.isNotEmpty()) activeStyle = styleStack.pop()
                                    if (groupTemplateStack.isNotEmpty()) groupTemplateStack.pop()
                                }
                                "clippath" -> {
                                    inDefs = (inDefs - 1).coerceAtLeast(0)
                                    activeClipPathDef = null
                                }
                                "mask" -> {
                                    inDefs = (inDefs - 1).coerceAtLeast(0)
                                    activeMaskDef = null
                                }
                                "style" -> inStyleTag = false
                                "lineargradient", "radialgradient" -> activeGradient = null
                                "g" -> {
                                    if (matrixStack.isNotEmpty()) activeTransform = matrixStack.pop()
                                    if (styleStack.isNotEmpty()) activeStyle = styleStack.pop()
                                    if (groupTemplateStack.isNotEmpty()) groupTemplateStack.pop()
                                }
                                "text" -> {
                                    val finalStr = activeTextBuilder?.toString()?.trim() ?: ""
                                    if (finalStr.isNotEmpty() && inDefs == 0) {
                                        val fillCol = (activeTextStyle.fillColor ?: Color.BLACK).let {
                                            applyAlpha(it, activeTextStyle.opacity * activeTextStyle.fillOpacity)
                                        }
                                        texts.add(
                                            SvgTextElement(
                                                text = finalStr,
                                                x = activeTextX,
                                                y = activeTextY,
                                                fontSize = activeTextStyle.fontSize,
                                                fillColor = fillCol,
                                                textAlign = activeTextStyle.textAnchor,
                                                transform = activeTextMatrix,
                                                activeClipIds = activeTextStyle.clipPathIds
                                            )
                                        )
                                    }
                                    activeTextBuilder = null
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }

                if (!sawSvgRoot) {
                    lastErrorMsg = "Документ не содержит корневого тега <svg>."
                } else if (shapes.isEmpty() && texts.isEmpty() && embeddedImages.isEmpty() && lastErrorMsg == null) {
                    lastErrorMsg = blockedImageError ?: "Векторный документ SVG не содержит поддерживаемых графических элементов."
                }
            }
        } catch (e: Exception) {
            if (shapes.isEmpty() && texts.isEmpty() && embeddedImages.isEmpty()) {
                lastErrorMsg = "Ошибка разбора XML-структуры SVG: ${e.localizedMessage ?: "Invalid SVG"}"
            }
        }
    }

    private fun handleUseElement(parser: XmlPullParser, inDefs: Int) {
        val href = (parser.getAttributeValue(null, "href")
            ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href"))
            ?.removePrefix("#")?.trim() ?: return
        val currentId = parser.getAttributeValue(null, "id")?.trim()
        if (href.isEmpty() || href == currentId) return

        val elemStyle = resolveElementStyle(parser, activeStyle)
        val ux = parseCoord(parser.getAttributeValue(null, "x"), 0f)
        val uy = parseCoord(parser.getAttributeValue(null, "y"), 0f)
        val uw = parseCoord(parser.getAttributeValue(null, "width"), 0f)
        val uh = parseCoord(parser.getAttributeValue(null, "height"), 0f)

        val useMatrix = Matrix(activeTransform)
        applyTransform(useMatrix, parser.getAttributeValue(null, "transform"))
        if (ux != 0f || uy != 0f) {
            useMatrix.preTranslate(ux, uy)
        }

        // 1. Check if <use> references a <g> or <symbol> group template
        val groupTemplate = defsGroups[href]
        if (groupTemplate != null && groupTemplate.shapes.isNotEmpty()) {
            val symbolMatrix = Matrix(useMatrix)
            val vb = groupTemplate.viewBox
            if (vb != null && uw > 0f && uh > 0f && vb.width() > 0f && vb.height() > 0f) {
                symbolMatrix.preScale(uw / vb.width(), uh / vb.height())
                symbolMatrix.preTranslate(-vb.left, -vb.top)
            }
            for (tmplShape in groupTemplate.shapes) {
                if (shapes.size >= MAX_SVG_SHAPES) break
                val clonedPath = Path(tmplShape.path)
                clonedPath.transform(symbolMatrix)
                val clonedShape = tmplShape.copy(
                    path = clonedPath,
                    fillColor = tmplShape.fillColor ?: elemStyle.fillColor?.let { applyAlpha(it, elemStyle.opacity * elemStyle.fillOpacity) },
                    strokeColor = tmplShape.strokeColor ?: elemStyle.strokeColor?.let { applyAlpha(it, elemStyle.opacity * elemStyle.strokeOpacity) }
                )
                if (inDefs == 0) {
                    shapes.add(clonedShape)
                    val pb = RectF()
                    clonedPath.computeBounds(pb, true)
                    if (!pb.isEmpty) {
                        if (bounds.isEmpty) bounds.set(pb) else bounds.union(pb)
                    }
                }
            }
            return
        }

        // 2. Fallback to single primitive path in defsPaths
        val templatePath = defsPaths[href] ?: return
        val clonedPath = Path(templatePath)
        clonedPath.fillType = elemStyle.fillRule
        clonedPath.transform(useMatrix)

        val fillCol = elemStyle.fillColor?.let { applyAlpha(it, elemStyle.opacity * elemStyle.fillOpacity) }
        val strokeCol = elemStyle.strokeColor?.let { applyAlpha(it, elemStyle.opacity * elemStyle.strokeOpacity) }
        val created = SvgShape(
            path = clonedPath,
            fillColor = fillCol,
            fillGradientId = elemStyle.fillGradientId,
            strokeColor = strokeCol,
            strokeGradientId = elemStyle.strokeGradientId,
            strokeWidth = elemStyle.strokeWidth,
            strokeCap = elemStyle.strokeCap,
            strokeJoin = elemStyle.strokeJoin,
            activeClipIds = elemStyle.clipPathIds,
            activeMaskId = elemStyle.maskId
        )
        if (inDefs == 0) {
            shapes.add(created)
            val pb = RectF()
            clonedPath.computeBounds(pb, true)
            if (!pb.isEmpty) {
                if (bounds.isEmpty) bounds.set(pb) else bounds.union(pb)
            }
        }
    }

    private fun parseEmbeddedImageElement(parser: XmlPullParser): String? {
        val elemStyle = resolveElementStyle(parser, activeStyle)
        val x = parseCoord(parser.getAttributeValue(null, "x"), 0f)
        val y = parseCoord(parser.getAttributeValue(null, "y"), 0f)
        val w = parseCoord(parser.getAttributeValue(null, "width"), 0f)
        val h = parseCoord(parser.getAttributeValue(null, "height"), 0f)
        if (w <= 0f || h <= 0f) return null

        val href = (parser.getAttributeValue(null, "href")
            ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href"))?.trim() ?: return null

        if (!href.startsWith("data:image/", ignoreCase = true)) {
            return "Внешний ресурс <image> заблокирован политикой безопасности (разрешены только встроенные data:image ресурсы)."
        }

        val commaIdx = href.indexOf(',')
        if (commaIdx <= 0 || !href.substring(0, commaIdx).contains("base64", ignoreCase = true)) {
            return "Неподдерживаемый формат встроенного <image> (ожидается base64)."
        }

        val base64Payload = href.substring(commaIdx + 1).trim()
        if (base64Payload.length > MAX_INLINE_IMAGE_BYTES) return null

        return try {
            val decodedBytes = Base64.decode(base64Payload, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            if (bmp != null) {
                val localMatrix = Matrix(activeTransform)
                applyTransform(localMatrix, parser.getAttributeValue(null, "transform"))
                val rect = RectF(x, y, x + w, y + h)
                embeddedImages.add(
                    SvgEmbeddedImage(
                        bitmap = bmp,
                        dstRect = rect,
                        transform = localMatrix,
                        opacity = elemStyle.opacity,
                        activeClipIds = elemStyle.clipPathIds,
                        activeMaskId = elemStyle.maskId
                    )
                )
                val mappedRect = RectF(rect)
                localMatrix.mapRect(mappedRect)
                if (bounds.isEmpty) bounds.set(mappedRect) else bounds.union(mappedRect)
                null
            } else {
                "Не удалось декодировать встроенное растровое изображение в <image>."
            }
        } catch (_: Exception) {
            "Повреждённые данные base64 внутри тега <image>."
        }
    }

    private fun buildShapePath(name: String, parser: XmlPullParser, elemStyle: SvgStyle): Path? {
        val path = Path()
        when (name) {
            "path" -> {
                val d = parser.getAttributeValue(null, "d") ?: return null
                return try {
                    PathParser.createPathFromPathData(d)
                } catch (_: Exception) { null }
            }
            "rect" -> {
                val x = parseCoord(parser.getAttributeValue(null, "x"), 0f)
                val y = parseCoord(parser.getAttributeValue(null, "y"), 0f)
                val w = parseCoord(parser.getAttributeValue(null, "width"), 0f)
                val h = parseCoord(parser.getAttributeValue(null, "height"), 0f)
                if (w <= 0f || h <= 0f) return null
                val rx = parseCoord(parser.getAttributeValue(null, "rx"), 0f)
                val ry = parseCoord(parser.getAttributeValue(null, "ry"), rx)
                if (rx > 0f || ry > 0f) {
                    path.addRoundRect(RectF(x, y, x + w, y + h), maxOf(rx, ry), maxOf(ry, rx), Path.Direction.CW)
                } else {
                    path.addRect(x, y, x + w, y + h, Path.Direction.CW)
                }
                return path
            }
            "circle" -> {
                val cx = parseCoord(parser.getAttributeValue(null, "cx"), 0f)
                val cy = parseCoord(parser.getAttributeValue(null, "cy"), 0f)
                val r = parseCoord(parser.getAttributeValue(null, "r"), 0f)
                if (r <= 0f) return null
                path.addCircle(cx, cy, r, Path.Direction.CW)
                return path
            }
            "ellipse" -> {
                val cx = parseCoord(parser.getAttributeValue(null, "cx"), 0f)
                val cy = parseCoord(parser.getAttributeValue(null, "cy"), 0f)
                val rx = parseCoord(parser.getAttributeValue(null, "rx"), 0f)
                val ry = parseCoord(parser.getAttributeValue(null, "ry"), 0f)
                if (rx <= 0f || ry <= 0f) return null
                path.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
                return path
            }
            "line" -> {
                val x1 = parseCoord(parser.getAttributeValue(null, "x1"), 0f)
                val y1 = parseCoord(parser.getAttributeValue(null, "y1"), 0f)
                val x2 = parseCoord(parser.getAttributeValue(null, "x2"), 0f)
                val y2 = parseCoord(parser.getAttributeValue(null, "y2"), 0f)
                elemStyle.fillColor = null
                if (elemStyle.strokeColor == null && elemStyle.strokeGradientId == null) {
                    elemStyle.strokeColor = Color.BLACK
                }
                path.moveTo(x1, y1)
                path.lineTo(x2, y2)
                return path
            }
            "polyline", "polygon" -> {
                val ptsData = parser.getAttributeValue(null, "points") ?: return null
                val parts = ptsData.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                if (parts.size < 4) return null
                if (name == "polyline" && parser.getAttributeValue(null, "fill") == null) {
                    elemStyle.fillColor = null
                    if (elemStyle.strokeColor == null) elemStyle.strokeColor = Color.BLACK
                }
                path.moveTo(parts[0], parts[1])
                for (i in 2 until parts.size step 2) {
                    if (i + 1 < parts.size) path.lineTo(parts[i], parts[i + 1])
                }
                if (name == "polygon") path.close()
                return path
            }
            else -> return null
        }
    }

    private fun parseCssBlock(cssText: String) {
        val clean = cssText.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val ruleRegex = Regex("([^{]+)\\{([^}]+)\\}")
        for (match in ruleRegex.findAll(clean)) {
            val selectors = match.groupValues[1].split(",")
            val body = match.groupValues[2]
            val propMap = mutableMapOf<String, String>()
            for (decl in body.split(";")) {
                val kv = decl.split(":")
                if (kv.size == 2) {
                    propMap[kv[0].trim().lowercase(Locale.ROOT)] = kv[1].trim()
                }
            }
            for (sel in selectors) {
                val s = sel.trim()
                if (s.isNotEmpty()) {
                    cssRules[s] = (cssRules[s] ?: emptyMap()) + propMap
                }
            }
        }
    }

    private fun extractUrlId(urlValue: String): String? {
        if (!urlValue.startsWith("url(", ignoreCase = true)) return null
        return urlValue.substringAfter("(")
            .substringBefore(")")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removePrefix("#")
            .takeIf { it.isNotEmpty() }
    }

    private fun resolveElementStyle(parser: XmlPullParser, parent: SvgStyle): SvgStyle {
        val style = parent.copy()
        val tagName = parser.name?.lowercase(Locale.ROOT) ?: ""
        val classAttr = parser.getAttributeValue(null, "class")
        val idAttr = parser.getAttributeValue(null, "id")

        val mergedProps = mutableMapOf<String, String>()
        cssRules[tagName]?.let { mergedProps.putAll(it) }
        if (classAttr != null) {
            for (cls in classAttr.trim().split(Regex("\\s+"))) {
                cssRules[".$cls"]?.let { mergedProps.putAll(it) }
                cssRules["$tagName.$cls"]?.let { mergedProps.putAll(it) }
            }
        }
        if (idAttr != null) {
            cssRules["#$idAttr"]?.let { mergedProps.putAll(it) }
        }

        val attrNames = listOf(
            "fill", "stroke", "stroke-width", "opacity", "fill-opacity",
            "stroke-opacity", "font-size", "text-anchor", "fill-rule",
            "stroke-linecap", "stroke-linejoin", "clip-path", "mask"
        )
        for (attr in attrNames) {
            parser.getAttributeValue(null, attr)?.let { mergedProps[attr] = it.trim() }
        }

        val inlineStyle = parser.getAttributeValue(null, "style")
        if (inlineStyle != null) {
            for (prop in inlineStyle.split(";")) {
                val kv = prop.split(":")
                if (kv.size == 2) {
                    mergedProps[kv[0].trim().lowercase(Locale.ROOT)] = kv[1].trim()
                }
            }
        }

        mergedProps["clip-path"]?.let { clipVal ->
            extractUrlId(clipVal)?.let { cid ->
                if (cid !in style.clipPathIds && style.clipPathIds.size < 8) {
                    style.clipPathIds = style.clipPathIds + cid
                }
            }
        }

        mergedProps["mask"]?.let { maskVal ->
            extractUrlId(maskVal)?.let { mid ->
                style.maskId = mid
            }
        }

        mergedProps["fill"]?.let { fillVal ->
            when {
                fillVal.equals("none", true) || fillVal.equals("transparent", true) -> {
                    style.fillColor = null
                    style.fillGradientId = null
                }
                fillVal.startsWith("url(", true) -> {
                    style.fillGradientId = extractUrlId(fillVal)
                    style.fillColor = null
                }
                else -> {
                    parseSvgColor(fillVal)?.let {
                        style.fillColor = it
                        style.fillGradientId = null
                    }
                }
            }
        }

        mergedProps["stroke"]?.let { strokeVal ->
            when {
                strokeVal.equals("none", true) || strokeVal.equals("transparent", true) -> {
                    style.strokeColor = null
                    style.strokeGradientId = null
                }
                strokeVal.startsWith("url(", true) -> {
                    style.strokeGradientId = extractUrlId(strokeVal)
                    style.strokeColor = null
                }
                else -> {
                    parseSvgColor(strokeVal)?.let {
                        style.strokeColor = it
                        style.strokeGradientId = null
                    }
                }
            }
        }

        mergedProps["stroke-width"]?.let { style.strokeWidth = parseCoord(it, style.strokeWidth) }
        mergedProps["opacity"]?.toFloatOrNull()?.let { if (it.isFinite()) style.opacity = it.coerceIn(0f, 1f) }
        mergedProps["fill-opacity"]?.toFloatOrNull()?.let { if (it.isFinite()) style.fillOpacity = it.coerceIn(0f, 1f) }
        mergedProps["stroke-opacity"]?.toFloatOrNull()?.let { if (it.isFinite()) style.strokeOpacity = it.coerceIn(0f, 1f) }
        mergedProps["font-size"]?.let { style.fontSize = parseCoord(it, style.fontSize) }

        mergedProps["text-anchor"]?.lowercase(Locale.ROOT)?.let {
            style.textAnchor = when (it) {
                "middle" -> Paint.Align.CENTER
                "end" -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
        }

        mergedProps["fill-rule"]?.lowercase(Locale.ROOT)?.let {
            style.fillRule = if (it == "evenodd") Path.FillType.EVEN_ODD else Path.FillType.WINDING
        }

        mergedProps["stroke-linecap"]?.lowercase(Locale.ROOT)?.let {
            style.strokeCap = when (it) {
                "round" -> Paint.Cap.ROUND
                "square" -> Paint.Cap.SQUARE
                else -> Paint.Cap.BUTT
            }
        }

        mergedProps["stroke-linejoin"]?.lowercase(Locale.ROOT)?.let {
            style.strokeJoin = when (it) {
                "round" -> Paint.Join.ROUND
                "bevel" -> Paint.Join.BEVEL
                else -> Paint.Join.MITER
            }
        }

        return style
    }

    private fun parseCoord(raw: String?, defaultVal: Float): Float {
        if (raw.isNullOrBlank()) return defaultVal
        val cleaned = raw.trim()
            .removeSuffix("px")
            .removeSuffix("pt")
            .removeSuffix("em")
            .removeSuffix("%")
            .trim()
        val v = cleaned.toFloatOrNull() ?: return defaultVal
        return if (v.isFinite()) v else defaultVal
    }

    private fun parseSvgColor(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val c = raw.trim().lowercase(Locale.ROOT)
        if (c == "none" || c == "transparent") return null
        if (c == "currentcolor") return Color.BLACK

        if (c.startsWith("#") && c.length == 4) {
            val r = c[1]; val g = c[2]; val b = c[3]
            return try { Color.parseColor("#$r$r$g$g$b$b") } catch (_: Exception) { null }
        }
        if (c.startsWith("rgb(") && c.endsWith(")")) {
            val nums = c.removePrefix("rgb(").removeSuffix(")").split(",").map { it.trim() }
            if (nums.size == 3) {
                val r = nums[0].removeSuffix("%").toIntOrNull()?.coerceIn(0, 255) ?: 0
                val g = nums[1].removeSuffix("%").toIntOrNull()?.coerceIn(0, 255) ?: 0
                val b = nums[2].removeSuffix("%").toIntOrNull()?.coerceIn(0, 255) ?: 0
                return Color.rgb(r, g, b)
            }
        }

        val namedColors = mapOf(
            "orange" to Color.rgb(255, 165, 0),
            "gold" to Color.rgb(255, 215, 0),
            "pink" to Color.rgb(255, 192, 203),
            "brown" to Color.rgb(165, 42, 42),
            "violet" to Color.rgb(238, 130, 238),
            "indigo" to Color.rgb(75, 0, 130),
            "teal" to Color.rgb(0, 128, 128),
            "navy" to Color.rgb(0, 0, 128),
            "coral" to Color.rgb(255, 127, 80),
            "crimson" to Color.rgb(220, 20, 60),
            "skyblue" to Color.rgb(135, 206, 235),
            "limegreen" to Color.rgb(50, 205, 50)
        )
        namedColors[c]?.let { return it }

        return try { Color.parseColor(c) } catch (_: Exception) { null }
    }

    private fun applyAlpha(color: Int, factor: Float): Int {
        val safeFactor = if (factor.isFinite()) factor.coerceIn(0f, 1f) else 1f
        val alpha = (Color.alpha(color) * safeFactor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun applyTransform(targetMatrix: Matrix, transformData: String?) {
        if (transformData.isNullOrBlank()) return
        val regex = Regex("([a-zA-Z]+)\\s*\\(([^)]+)\\)")

        for (match in regex.findAll(transformData)) {
            val func = match.groupValues[1].lowercase(Locale.ROOT)
            val args = match.groupValues[2].trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }.filter { it.isFinite() }
            if (args.isEmpty()) continue

            val local = Matrix()
            when (func) {
                "translate" -> {
                    val tx = args.getOrElse(0) { 0f }
                    val ty = args.getOrElse(1) { 0f }
                    local.setTranslate(tx, ty)
                }
                "scale" -> {
                    val sx = args.getOrElse(0) { 1f }
                    val sy = args.getOrElse(1) { sx }
                    local.setScale(sx, sy)
                }
                "rotate" -> {
                    val angle = args.getOrElse(0) { 0f }
                    if (args.size >= 3) {
                        local.setRotate(angle, args[1], args[2])
                    } else {
                        local.setRotate(angle)
                    }
                }
                "skewx" -> {
                    val rad = Math.toRadians(args[0].toDouble())
                    val t = tan(rad).toFloat()
                    if (t.isFinite()) local.setSkew(t, 0f)
                }
                "skewy" -> {
                    val rad = Math.toRadians(args[0].toDouble())
                    val t = tan(rad).toFloat()
                    if (t.isFinite()) local.setSkew(0f, t)
                }
                "matrix" -> {
                    if (args.size >= 6) {
                        local.setValues(
                            floatArrayOf(
                                args[0], args[2], args[4],
                                args[1], args[3], args[5],
                                0f, 0f, 1f
                            )
                        )
                    }
                }
            }
            targetMatrix.preConcat(local)
        }
    }

    private fun mapGradientCoord(value: Float, minBound: Float, span: Float, isUserSpace: Boolean): Float {
        if (isUserSpace) return value
        val norm = if (value > 1.001f && value <= 100f) value / 100f else value
        return minBound + norm * span
    }

    private fun buildShader(gradientId: String, shapeBounds: RectF): Shader? {
        val grad = gradients[gradientId] ?: return null
        val bw = shapeBounds.width().coerceAtLeast(1f)
        val bh = shapeBounds.height().coerceAtLeast(1f)

        return try {
            when (grad) {
                is SvgGradient.Linear -> {
                    if (grad.stops.isEmpty()) return null
                    if (!grad.x1.isFinite() || !grad.y1.isFinite() || !grad.x2.isFinite() || !grad.y2.isFinite()) return null
                    val sortedStops = grad.stops.sortedBy { if (it.offset.isFinite()) it.offset.coerceIn(0f, 1f) else 0f }
                    val colors = if (sortedStops.size == 1) {
                        intArrayOf(sortedStops[0].color, sortedStops[0].color)
                    } else {
                        sortedStops.map { it.color }.toIntArray()
                    }
                    val positions = if (sortedStops.size == 1) {
                        null
                    } else {
                        sortedStops.map { if (it.offset.isFinite()) it.offset.coerceIn(0f, 1f) else 0f }.toFloatArray()
                    }
                    val gx1 = mapGradientCoord(grad.x1, shapeBounds.left, bw, grad.isUserSpaceOnUse)
                    val gy1 = mapGradientCoord(grad.y1, shapeBounds.top, bh, grad.isUserSpaceOnUse)
                    val gx2 = mapGradientCoord(grad.x2, shapeBounds.left, bw, grad.isUserSpaceOnUse)
                    val gy2 = mapGradientCoord(grad.y2, shapeBounds.top, bh, grad.isUserSpaceOnUse)
                    val safeX2 = if (gx1 == gx2 && gy1 == gy2) gx2 + 0.001f else gx2
                    LinearGradient(gx1, gy1, safeX2, gy2, colors, positions, Shader.TileMode.CLAMP)
                }
                is SvgGradient.Radial -> {
                    if (grad.stops.isEmpty()) return null
                    if (!grad.cx.isFinite() || !grad.cy.isFinite() || !grad.r.isFinite()) return null
                    val sortedStops = grad.stops.sortedBy { if (it.offset.isFinite()) it.offset.coerceIn(0f, 1f) else 0f }
                    val colors = if (sortedStops.size == 1) {
                        intArrayOf(sortedStops[0].color, sortedStops[0].color)
                    } else {
                        sortedStops.map { it.color }.toIntArray()
                    }
                    val positions = if (sortedStops.size == 1) {
                        null
                    } else {
                        sortedStops.map { if (it.offset.isFinite()) it.offset.coerceIn(0f, 1f) else 0f }.toFloatArray()
                    }
                    val gcx = mapGradientCoord(grad.cx, shapeBounds.left, bw, grad.isUserSpaceOnUse)
                    val gcy = mapGradientCoord(grad.cy, shapeBounds.top, bh, grad.isUserSpaceOnUse)
                    val gr = if (grad.isUserSpaceOnUse) {
                        grad.r
                    } else {
                        val normR = if (grad.r > 1.001f && grad.r <= 100f) grad.r / 100f else grad.r
                        normR * max(bw, bh)
                    }.coerceAtLeast(0.001f)
                    RadialGradient(gcx, gcy, gr, colors, positions, Shader.TileMode.CLAMP)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyClipsForElement(canvas: Canvas, clipIds: List<String>, elementBounds: RectF) {
        for (cid in clipIds) {
            val def = clipPaths[cid] ?: continue
            if (def.objectBoundingBoxUnits) {
                val m = Matrix()
                m.postScale(elementBounds.width().coerceAtLeast(1f), elementBounds.height().coerceAtLeast(1f))
                m.postTranslate(elementBounds.left, elementBounds.top)
                val scaledClip = Path(def.combinedPath)
                scaledClip.transform(m)
                canvas.clipPath(scaledClip)
            } else {
                canvas.clipPath(def.combinedPath)
            }
        }
    }

    private fun drawShapeInternal(canvas: Canvas, shape: SvgShape, paint: Paint, shapeBounds: RectF) {
        if (shape.fillGradientId != null) {
            val shader = buildShader(shape.fillGradientId, shapeBounds)
            if (shader != null) {
                paint.style = Paint.Style.FILL
                paint.shader = shader
                canvas.drawPath(shape.path, paint)
                paint.shader = null
            }
        } else if (shape.fillColor != null) {
            paint.style = Paint.Style.FILL
            paint.color = shape.fillColor
            canvas.drawPath(shape.path, paint)
        }

        if (shape.strokeGradientId != null) {
            val shader = buildShader(shape.strokeGradientId, shapeBounds)
            if (shader != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = shape.strokeWidth
                paint.strokeCap = shape.strokeCap
                paint.strokeJoin = shape.strokeJoin
                paint.shader = shader
                canvas.drawPath(shape.path, paint)
                paint.shader = null
            }
        } else if (shape.strokeColor != null && shape.strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = shape.strokeWidth
            paint.strokeCap = shape.strokeCap
            paint.strokeJoin = shape.strokeJoin
            paint.color = shape.strokeColor
            canvas.drawPath(shape.path, paint)
        }
    }

    private val luminanceToAlphaFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0.2126f, 0.7152f, 0.0722f, 0f, 0f
            )
        )
    )

    override val pageCount: Int = 1

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        if (shapes.isEmpty() && texts.isEmpty() && embeddedImages.isEmpty()) return

        val paint = Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.SANS_SERIF
        }

        val targetBounds = currentViewBox ?: bounds
        val docW = targetBounds.width().let { if (it <= 0f) 500f else it }
        val docH = targetBounds.height().let { if (it <= 0f) 500f else it }

        val fitScale = minOf(
            (bitmap.width - 80f) / docW,
            (bitmap.height - 80f) / docH
        ).coerceAtLeast(0.001f)

        val userZoom = (zoomPermille / 1000f).coerceIn(0.25f, 10f)
        val totalScale = fitScale * userZoom

        val dx = (bitmap.width - docW * totalScale) / 2f - targetBounds.left * totalScale - tileX
        val dy = (bitmap.height - docH * totalScale) / 2f - targetBounds.top * totalScale - tileY

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(totalScale, totalScale)

        val elemBounds = RectF()
        val maskShapeBounds = RectF()

        for (shape in shapes) {
            shape.path.computeBounds(elemBounds, true)
            val hasClip = shape.activeClipIds.isNotEmpty()
            val maskDef = shape.activeMaskId?.let { masks[it] }

            if (hasClip || maskDef != null) {
                canvas.save()
                if (hasClip) {
                    applyClipsForElement(canvas, shape.activeClipIds, elemBounds)
                }
                if (maskDef != null && maskDef.shapes.isNotEmpty()) {
                    val contentLayer = canvas.saveLayer(elemBounds, null)
                    drawShapeInternal(canvas, shape, paint, elemBounds)

                    val maskLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        colorFilter = luminanceToAlphaFilter
                    }
                    val maskSubLayer = canvas.saveLayer(elemBounds, maskLayerPaint)
                    val maskDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                    for (mShape in maskDef.shapes) {
                        mShape.path.computeBounds(maskShapeBounds, true)
                        drawShapeInternal(canvas, mShape, maskDrawPaint, maskShapeBounds)
                    }
                    canvas.restoreToCount(maskSubLayer)
                    canvas.restoreToCount(contentLayer)
                } else {
                    drawShapeInternal(canvas, shape, paint, elemBounds)
                }
                canvas.restore()
            } else {
                drawShapeInternal(canvas, shape, paint, elemBounds)
            }
        }

        for (img in embeddedImages) {
            if (!img.bitmap.isRecycled) {
                canvas.save()
                if (img.activeClipIds.isNotEmpty()) {
                    val mapped = RectF(img.dstRect)
                    img.transform.mapRect(mapped)
                    applyClipsForElement(canvas, img.activeClipIds, mapped)
                }
                canvas.concat(img.transform)
                val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (img.opacity * 255f).toInt().coerceIn(0, 255)
                }
                canvas.drawBitmap(img.bitmap, null, img.dstRect, imgPaint)
                canvas.restore()
            }
        }

        for (txtElem in texts) {
            canvas.save()
            if (txtElem.activeClipIds.isNotEmpty()) {
                applyClipsForElement(canvas, txtElem.activeClipIds, targetBounds)
            }
            canvas.concat(txtElem.transform)
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = txtElem.fillColor ?: Color.BLACK
            paint.textSize = txtElem.fontSize
            paint.textAlign = txtElem.textAlign
            canvas.drawText(txtElem.text, txtElem.x, txtElem.y, paint)
            canvas.restore()
        }

        canvas.restore()
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        embeddedImages.forEach {
            if (!it.bitmap.isRecycled) it.bitmap.recycle()
        }
        embeddedImages.clear()
        try { pfd.close() } catch (_: Exception) {}
    }
}