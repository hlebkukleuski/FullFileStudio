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

package me.hlebkukleuski.fullfilestudio.feature.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.omnireader.core.engine.impl.EbookTextSession
import com.omnireader.core.engine.impl.EmbeddedImageScanner
import com.omnireader.core.engine.impl.OfficeFonts
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap

internal object CleanRoomOdpEngine {
    data class OdpGradient(
        val style: String,
        val startColor: Int,
        val endColor: Int,
        val angleDeg: Float
    )

    data class OdpStyle(
        val fillMode: String? = null,
        val fillColor: Int? = null,
        val fillGradientName: String? = null,
        val fillImageName: String? = null,
        val opacity: Float = 1f,
        val strokeMode: String? = null,
        val strokeColor: Int? = null,
        val strokeWidthCm: Float? = null,
        val strokeDashed: Boolean = false,
        val markerEnd: Boolean = false,
        val markerStart: Boolean = false,
        val textColor: Int? = null,
        val fontSizePt: Float? = null,
        val fontFamily: String? = null,
        val isBold: Boolean? = null,
        val isItalic: Boolean? = null,
        val isUnderline: Boolean? = null,
        val isStrike: Boolean? = null,
        val textAlign: String? = null,
        val vAlign: String? = null,
        val padLeftCm: Float? = null,
        val padRightCm: Float? = null,
        val padTopCm: Float? = null,
        val padBottomCm: Float? = null,
        val marginLeftCm: Float? = null,
        val marginTopCm: Float? = null,
        val marginBottomCm: Float? = null,
        val bgColor: Int? = null,
        val borderAll: Pair<Int, Float>? = null,
        val borderTop: Pair<Int, Float>? = null,
        val borderBottom: Pair<Int, Float>? = null,
        val borderLeft: Pair<Int, Float>? = null,
        val borderRight: Pair<Int, Float>? = null,
        val colWidthCm: Float? = null,
        val rowHeightCm: Float? = null
    )

    data class OdpRun(
        val text: String,
        val color: Int? = null,
        val fontSizePt: Float? = null,
        val fontFamily: String? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val isPageNumber: Boolean = false
    )

    data class OdpParagraph(
        val runs: List<OdpRun>,
        val align: String? = null,
        val bullet: String? = null,
        val listLevel: Int = 0,
        val marginLeftCm: Float = 0f,
        val spaceBeforeCm: Float = 0f,
        val spaceAfterCm: Float = 0f
    )

    data class OdpTableCell(
        val paragraphs: List<OdpParagraph>,
        val colSpan: Int = 1,
        val rowSpan: Int = 1,
        val covered: Boolean = false,
        val bgColor: Int? = null,
        val borderColor: Int? = null,
        val vAlign: String? = null
    )

    data class OdpTable(
        val colWidthsCm: List<Float>,
        val rows: List<List<OdpTableCell>>
    )

    data class OdpBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    data class OdpElement(
        val kind: String,
        val rectCm: OdpBox?,
        val paragraphs: List<OdpParagraph> = emptyList(),
        val table: OdpTable? = null,
        val imagePath: String? = null,
        val fillColor: Int? = null,
        val gradient: OdpGradient? = null,
        val fillImagePath: String? = null,
        val opacity: Float = 1f,
        val strokeColor: Int? = null,
        val strokeWidthCm: Float = 0.035f,
        val strokeDashed: Boolean = false,
        val markerEnd: Boolean = false,
        val markerStart: Boolean = false,
        val cornerRadiusCm: Float = 0f,
        val customType: String? = null,
        val svgPathD: String? = null,
        val pointsStr: String? = null,
        val viewBox: OdpBox? = null,
        val transformMatrix: FloatArray? = null,
        val vAlign: String? = null,
        val padCm: FloatArray = floatArrayOf(0.18f, 0.12f, 0.18f, 0.12f),
        val presClass: String? = null
    )

    data class OdpSlide(
        val name: String,
        val slideNumber: Int,
        val backgroundColor: Int,
        val backgroundGradient: OdpGradient?,
        val backgroundImagePath: String?,
        val masterElements: List<OdpElement>,
        val elements: List<OdpElement>,
        val notesText: String,
        val plainText: String
    )

    data class OdpDocument(
        val pageWidthCm: Float,
        val pageHeightCm: Float,
        val slides: List<OdpSlide>,
        val mediaMap: Map<String, ByteArray>
    )

    private class XmlNode(
        val local: String,
        val attrs: Map<String, String>,
        val children: ArrayList<XmlNode> = ArrayList(),
        val textParts: ArrayList<Any> = ArrayList()
    ) {
        fun attr(suffix: String): String? = attrs[suffix.lowercase(Locale.ROOT)]
        fun child(localName: String): XmlNode? = children.firstOrNull { it.local == localName }
        fun childrenNamed(localName: String): List<XmlNode> = children.filter { it.local == localName }
    }

    private data class RawStyle(
        val name: String,
        val family: String,
        val parent: String?,
        val props: HashMap<String, String>
    )

    private data class ListLevelDef(
        val bullet: String?,
        val numFmt: String?,
        val prefix: String,
        val suffix: String,
        val start: Int
    )

    private data class MasterPageDef(
        val name: String,
        val pageLayoutName: String?,
        val drawStyleName: String?,
        val elements: List<OdpElement>
    )

    fun isEncryptedOdf(entries: Map<String, ByteArray>): Boolean {
        val manifest = entries.entries.firstOrNull { it.key.equals("META-INF/manifest.xml", ignoreCase = true) }?.value ?: return false
        val txt = String(manifest, StandardCharsets.UTF_8)
        return txt.contains("encryption-data", ignoreCase = true)
    }

    fun parse(entries: Map<String, ByteArray>): OdpDocument? {
        if (isEncryptedOdf(entries)) {
            throw SecurityException("PASSWORD_REQUIRED: ODP presentation is encrypted or password protected.")
        }
        val mediaMap = TreeMap<String, ByteArray>(String.CASE_INSENSITIVE_ORDER)
        var contentBytes: ByteArray? = null
        var stylesBytes: ByteArray? = null
        for ((k, v) in entries) {
            val norm = k.replace('\\', '/').trimStart('/')
            when {
                norm.equals("content.xml", ignoreCase = true) -> contentBytes = v
                norm.equals("styles.xml", ignoreCase = true) -> stylesBytes = v
                norm.startsWith("Pictures/", ignoreCase = true) || norm.startsWith("media/", ignoreCase = true) -> mediaMap[norm] = v
            }
        }
        val contentXml = contentBytes ?: return null
        val stylesRoot = stylesBytes?.let { parseXmlTree(it) }
        val contentRoot = parseXmlTree(contentXml) ?: return null

        val rawStyles = HashMap<String, RawStyle>()
        val defaultStyles = HashMap<String, RawStyle>()
        val gradients = HashMap<String, OdpGradient>()
        val fillImages = HashMap<String, String>()
        val pageLayouts = HashMap<String, Pair<Float, Float>>()
        val listStyles = HashMap<String, Map<Int, ListLevelDef>>()
        val fontFaces = HashMap<String, String>()

        fun collectDefs(root: XmlNode?) {
            if (root == null) return
            fun walk(n: XmlNode) {
                when (n.local) {
                    "font-face" -> {
                        val nm = n.attr("name")
                        val fam = n.attr("font-family")?.trim()?.trim('\'', '"')
                        if (nm != null && fam != null) fontFaces[nm] = fam
                    }
                    "gradient" -> {
                        val nm = n.attr("name") ?: return
                        val st = n.attr("style") ?: "linear"
                        val sc = parseColor(n.attr("start-color")) ?: 0xFFFFFFFF.toInt()
                        val ec = parseColor(n.attr("end-color")) ?: 0xFFCCCCCC.toInt()
                        val ang = (n.attr("angle")?.toFloatOrNull() ?: 0f) / 10f
                        gradients[nm] = OdpGradient(st, sc, ec, ang)
                    }
                    "fill-image" -> {
                        val nm = n.attr("name") ?: return
                        val href = n.attr("href")?.replace('\\', '/')?.trimStart('/') ?: return
                        fillImages[nm] = href
                    }
                    "page-layout" -> {
                        val nm = n.attr("name") ?: return
                        val props = n.child("page-layout-properties") ?: return
                        val pw = parseLengthToCm(props.attr("page-width"))
                        val ph = parseLengthToCm(props.attr("page-height"))
                        if (pw != null && ph != null && pw > 2f && ph > 2f) pageLayouts[nm] = pw to ph
                    }
                    "style" -> {
                        val nm = n.attr("name") ?: return
                        val fam = n.attr("family") ?: ""
                        val par = n.attr("parent-style-name")
                        val props = HashMap<String, String>()
                        for (c in n.children) {
                            if (c.local.endsWith("-properties")) {
                                for ((ak, av) in c.attrs) props[ak] = av
                            }
                        }
                        rawStyles[nm] = RawStyle(nm, fam, par, props)
                        if (fam.isNotEmpty()) rawStyles["$fam/$nm"] = RawStyle(nm, fam, par, props)
                    }
                    "default-style" -> {
                        val fam = n.attr("family") ?: return
                        val props = HashMap<String, String>()
                        for (c in n.children) {
                            if (c.local.endsWith("-properties")) {
                                for ((ak, av) in c.attrs) props[ak] = av
                            }
                        }
                        defaultStyles[fam] = RawStyle("__default__", fam, null, props)
                    }
                    "list-style" -> {
                        val nm = n.attr("name") ?: return
                        val lvls = HashMap<Int, ListLevelDef>()
                        for (c in n.children) {
                            val lv = c.attr("level")?.toIntOrNull() ?: continue
                            when (c.local) {
                                "list-level-style-bullet" -> lvls[lv] = ListLevelDef(c.attr("bullet-char") ?: "•", null, c.attr("num-prefix") ?: "", c.attr("num-suffix") ?: "", 1)
                                "list-level-style-number" -> lvls[lv] = ListLevelDef(null, c.attr("num-format") ?: "1", c.attr("num-prefix") ?: "", c.attr("num-suffix") ?: ".", c.attr("start-value")?.toIntOrNull() ?: 1)
                            }
                        }
                        listStyles[nm] = lvls
                    }
                    else -> for (c in n.children) walk(c)
                }
            }
            walk(root)
        }

        collectDefs(stylesRoot)
        collectDefs(contentRoot)

        val resolvedCache = HashMap<String, OdpStyle>()
        fun resolveStyle(name: String?, familyHint: String? = null): OdpStyle {
            if (name.isNullOrBlank()) {
                val df = familyHint?.let { defaultStyles[it] } ?: return OdpStyle()
                return buildOdpStyle(df.props, fontFaces)
            }
            val key = "${familyHint ?: ""}|$name"
            resolvedCache[key]?.let { return it }
            val chain = ArrayList<RawStyle>()
            var cur = (familyHint?.let { rawStyles["$it/$name"] } ?: rawStyles[name])
            var guard = 0
            var fam = familyHint ?: cur?.family
            while (cur != null && guard++ < 24) {
                chain.add(cur)
                if (fam.isNullOrEmpty() && cur.family.isNotEmpty()) fam = cur.family
                val pName = cur.parent
                cur = if (pName != null && pName != cur.name) {
                    (fam?.let { rawStyles["$it/$pName"] } ?: rawStyles[pName])
                } else null
            }
            val merged = HashMap<String, String>()
            fam?.let { defaultStyles[it]?.props?.let { dp -> merged.putAll(dp) } }
            for (st in chain.asReversed()) merged.putAll(st.props)
            val built = buildOdpStyle(merged, fontFaces)
            resolvedCache[key] = built
            return built
        }

        fun mergeStyles(base: OdpStyle, overlay: OdpStyle): OdpStyle = OdpStyle(
            fillMode = overlay.fillMode ?: base.fillMode,
            fillColor = overlay.fillColor ?: base.fillColor,
            fillGradientName = overlay.fillGradientName ?: base.fillGradientName,
            fillImageName = overlay.fillImageName ?: base.fillImageName,
            opacity = if (overlay.opacity < 1f) overlay.opacity else base.opacity,
            strokeMode = overlay.strokeMode ?: base.strokeMode,
            strokeColor = overlay.strokeColor ?: base.strokeColor,
            strokeWidthCm = overlay.strokeWidthCm ?: base.strokeWidthCm,
            strokeDashed = overlay.strokeDashed || base.strokeDashed,
            markerEnd = overlay.markerEnd || base.markerEnd,
            markerStart = overlay.markerStart || base.markerStart,
            textColor = overlay.textColor ?: base.textColor,
            fontSizePt = overlay.fontSizePt ?: base.fontSizePt,
            fontFamily = overlay.fontFamily ?: base.fontFamily,
            isBold = overlay.isBold ?: base.isBold,
            isItalic = overlay.isItalic ?: base.isItalic,
            isUnderline = overlay.isUnderline ?: base.isUnderline,
            isStrike = overlay.isStrike ?: base.isStrike,
            textAlign = overlay.textAlign ?: base.textAlign,
            vAlign = overlay.vAlign ?: base.vAlign,
            padLeftCm = overlay.padLeftCm ?: base.padLeftCm,
            padRightCm = overlay.padRightCm ?: base.padRightCm,
            padTopCm = overlay.padTopCm ?: base.padTopCm,
            padBottomCm = overlay.padBottomCm ?: base.padBottomCm,
            marginLeftCm = overlay.marginLeftCm ?: base.marginLeftCm,
            marginTopCm = overlay.marginTopCm ?: base.marginTopCm,
            marginBottomCm = overlay.marginBottomCm ?: base.marginBottomCm,
            bgColor = overlay.bgColor ?: base.bgColor,
            borderAll = overlay.borderAll ?: base.borderAll,
            borderTop = overlay.borderTop ?: base.borderTop,
            borderBottom = overlay.borderBottom ?: base.borderBottom,
            borderLeft = overlay.borderLeft ?: base.borderLeft,
            borderRight = overlay.borderRight ?: base.borderRight,
            colWidthCm = overlay.colWidthCm ?: base.colWidthCm,
            rowHeightCm = overlay.rowHeightCm ?: base.rowHeightCm
        )

        fun parseParagraphs(container: XmlNode, baseStyle: OdpStyle, listStyleName: String?, listLevel: Int, listCounters: IntArray, out: MutableList<OdpParagraph>, summary: StringBuilder?) {
            for (ch in container.children) {
                when (ch.local) {
                    "list" -> {
                        val lsName = ch.attr("style-name") ?: listStyleName
                        val nextLvl = (listLevel + 1).coerceAtMost(9)
                        listCounters[nextLvl] = 0
                        for (item in ch.children) {
                            if (item.local == "list-item" || item.local == "list-header") {
                                parseParagraphs(item, baseStyle, lsName, nextLvl, listCounters, out, summary)
                            }
                        }
                    }
                    "p", "h" -> {
                        val pStyle = mergeStyles(baseStyle, resolveStyle(ch.attr("style-name"), "paragraph"))
                        val runs = ArrayList<OdpRun>()
                        fun walkInline(node: XmlNode, curStyle: OdpStyle) {
                            for (part in node.textParts) {
                                when (part) {
                                    is String -> if (part.isNotEmpty()) {
                                        runs.add(OdpRun(
                                            text = part,
                                            color = curStyle.textColor,
                                            fontSizePt = curStyle.fontSizePt,
                                            fontFamily = curStyle.fontFamily,
                                            bold = curStyle.isBold == true,
                                            italic = curStyle.isItalic == true,
                                            underline = curStyle.isUnderline == true,
                                            strike = curStyle.isStrike == true
                                        ))
                                    }
                                    is XmlNode -> when (part.local) {
                                        "span" -> walkInline(part, mergeStyles(curStyle, resolveStyle(part.attr("style-name"), "text")))
                                        "a" -> walkInline(part, curStyle)
                                        "s" -> {
                                            val cnt = (part.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                                            runs.add(OdpRun(" ".repeat(cnt), curStyle.textColor, curStyle.fontSizePt, curStyle.fontFamily, curStyle.isBold == true, curStyle.isItalic == true))
                                        }
                                        "tab" -> runs.add(OdpRun("    ", curStyle.textColor, curStyle.fontSizePt, curStyle.fontFamily))
                                        "line-break" -> runs.add(OdpRun("\n", curStyle.textColor, curStyle.fontSizePt, curStyle.fontFamily))
                                        "page-number" -> runs.add(OdpRun("#", curStyle.textColor, curStyle.fontSizePt, curStyle.fontFamily, curStyle.isBold == true, curStyle.isItalic == true, isPageNumber = true))
                                        "date-time", "footer", "header" -> walkInline(part, curStyle)
                                        else -> walkInline(part, curStyle)
                                    }
                                }
                            }
                        }
                        walkInline(ch, pStyle)
                        var bulletStr: String? = null
                        if (listLevel > 0 && runs.any { it.text.isNotBlank() }) {
                            val lvDef = listStyleName?.let { listStyles[it]?.get(listLevel) }
                            if (lvDef != null && lvDef.numFmt != null) {
                                if (listCounters[listLevel] == 0) listCounters[listLevel] = lvDef.start else listCounters[listLevel]++
                                val num = formatListNumber(listCounters[listLevel], lvDef.numFmt)
                                bulletStr = "${lvDef.prefix}$num${lvDef.suffix} "
                            } else {
                                val bch = lvDef?.bullet?.takeIf { it.isNotBlank() } ?: when (listLevel) { 1 -> "•"; 2 -> "◦"; else -> "▪" }
                                bulletStr = "$bch "
                            }
                        }
                        val plainLine = runs.joinToString("") { it.text }.trim()
                        if (runs.isNotEmpty()) {
                            out.add(OdpParagraph(
                                runs = runs,
                                align = pStyle.textAlign,
                                bullet = bulletStr,
                                listLevel = listLevel,
                                marginLeftCm = pStyle.marginLeftCm ?: (if (listLevel > 0) listLevel * 0.65f else 0f),
                                spaceBeforeCm = pStyle.marginTopCm ?: 0f,
                                spaceAfterCm = pStyle.marginBottomCm ?: 0.1f
                            ))
                            if (summary != null && plainLine.isNotEmpty() && summary.length < 65536) {
                                summary.append(plainLine).append('\n')
                            }
                        }
                    }
                }
            }
        }

        fun parseTableNode(tblNode: XmlNode, summary: StringBuilder?): OdpTable {
            val colWidths = ArrayList<Float>()
            val rows = ArrayList<List<OdpTableCell>>()
            for (ch in tblNode.children) {
                when (ch.local) {
                    "table-column" -> {
                        val rep = (ch.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                        val cw = resolveStyle(ch.attr("style-name"), "table-column").colWidthCm ?: 3.0f
                        repeat(rep) { if (colWidths.size < 64) colWidths.add(cw) }
                    }
                    "table-row" -> {
                        if (rows.size >= 256) continue
                        val rowCells = ArrayList<OdpTableCell>()
                        val rowTxt = ArrayList<String>()
                        for (c in ch.children) {
                            if (c.local != "table-cell" && c.local != "covered-table-cell") continue
                            val rep = (c.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                            val cSpan = (c.attr("number-columns-spanned")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                            val rSpan = (c.attr("number-rows-spanned")?.toIntOrNull() ?: 1).coerceIn(1, 256)
                            val cStyle = resolveStyle(c.attr("style-name"), "table-cell")
                            val paras = ArrayList<OdpParagraph>()
                            if (c.local == "table-cell") {
                                parseParagraphs(c, cStyle, null, 0, IntArray(10), paras, null)
                            }
                            val plainCell = paras.joinToString(" ") { p -> p.runs.joinToString("") { it.text } }.trim()
                            repeat(rep) {
                                if (rowCells.size < 64) {
                                    rowCells.add(OdpTableCell(
                                        paragraphs = paras,
                                        colSpan = if (it == 0) cSpan else 1,
                                        rowSpan = if (it == 0) rSpan else 1,
                                        covered = c.local == "covered-table-cell",
                                        bgColor = cStyle.bgColor ?: cStyle.fillColor,
                                        borderColor = cStyle.borderAll?.first ?: cStyle.borderTop?.first ?: cStyle.borderBottom?.first,
                                        vAlign = cStyle.vAlign
                                    ))
                                    if (plainCell.isNotEmpty()) rowTxt.add(plainCell)
                                }
                            }
                        }
                        if (rowCells.isNotEmpty()) {
                            rows.add(rowCells)
                            if (summary != null && rowTxt.isNotEmpty() && summary.length < 65536) {
                                summary.append(rowTxt.joinToString(" | ")).append('\n')
                            }
                        }
                    }
                }
            }
            return OdpTable(colWidths, rows)
        }

        fun parseElements(container: XmlNode, parentMatrix: FloatArray?, skipPlaceholders: Boolean, out: MutableList<OdpElement>, summary: StringBuilder?) {
            for (ch in container.children) {
                if (out.size >= 1024) break
                when (ch.local) {
                    "notes" -> {}
                    "g" -> {
                        val m = combineTransform(parentMatrix, ch.attr("transform"))
                        parseElements(ch, m, skipPlaceholders, out, summary)
                    }
                    "frame", "custom-shape", "rect", "ellipse", "circle", "line", "connector", "polygon", "polyline", "path" -> {
                        if (skipPlaceholders && ch.attr("placeholder") == "true") continue
                        val presClass = ch.attr("class")
                        val gStyleName = ch.attr("style-name")
                        val tStyleName = ch.attr("text-style-name")
                        var st = resolveStyle(gStyleName, if (presClass != null) "presentation" else "graphic")
                        if (tStyleName != null) st = mergeStyles(st, resolveStyle(tStyleName, "paragraph"))
                        val rect = if (ch.local == "line" || ch.local == "connector") {
                            val x1 = parseLengthToCm(ch.attr("x1")) ?: 0f
                            val y1 = parseLengthToCm(ch.attr("y1")) ?: 0f
                            val x2 = parseLengthToCm(ch.attr("x2")) ?: 0f
                            val y2 = parseLengthToCm(ch.attr("y2")) ?: 0f
                            OdpBox(x1, y1, x2, y2)
                        } else {
                            val x = parseLengthToCm(ch.attr("x")) ?: 0f
                            val y = parseLengthToCm(ch.attr("y")) ?: 0f
                            val w = parseLengthToCm(ch.attr("width"))
                            val h = parseLengthToCm(ch.attr("height"))
                            if (w != null && h != null && w > 0f && h > 0f) OdpBox(x, y, x + w, y + h) else null
                        }
                        val matArr = combineTransform(parentMatrix, ch.attr("transform"))

                        val paras = ArrayList<OdpParagraph>()
                        var tbl: OdpTable? = null
                        var imgPath: String? = null
                        if (ch.local == "frame") {
                            for (sub in ch.children) {
                                when (sub.local) {
                                    "text-box" -> parseParagraphs(sub, st, null, 0, IntArray(10), paras, summary)
                                    "image" -> {
                                        val href = sub.attr("href")?.replace('\\', '/')?.trimStart('/')
                                        if (!href.isNullOrBlank() && mediaMap.containsKey(href)) imgPath = href
                                    }
                                    "table" -> tbl = parseTableNode(sub, summary)
                                }
                            }
                        } else {
                            parseParagraphs(ch, st, null, 0, IntArray(10), paras, summary)
                        }

                        val isShapeKind = ch.local in setOf("custom-shape", "rect", "ellipse", "circle", "polygon", "polyline", "path")
                        val fillNone = st.fillMode.equals("none", ignoreCase = true)
                        val fillCol = if (fillNone) null else when (st.fillMode?.lowercase(Locale.ROOT)) {
                            "gradient", "bitmap", "hatch" -> null
                            else -> st.fillColor ?: if (isShapeKind && ch.local != "polyline" && st.fillMode == null && gStyleName == null) 0xFF729FCF.toInt() else null
                        }
                        val grad = if (!fillNone && st.fillMode.equals("gradient", ignoreCase = true)) st.fillGradientName?.let { gradients[it] } else null
                        val fImg = if (!fillNone && st.fillMode.equals("bitmap", ignoreCase = true)) st.fillImageName?.let { fillImages[it] } else null
                        val strokeNone = st.strokeMode.equals("none", ignoreCase = true)
                        val strokeCol = if (strokeNone) null else st.strokeColor ?: when {
                            ch.local == "line" || ch.local == "connector" -> 0xFF3C4150.toInt()
                            isShapeKind && st.strokeMode == null && gStyleName == null -> 0xFF3465A4.toInt()
                            else -> null
                        }

                        val enh = ch.child("enhanced-geometry")
                        val customType = enh?.attr("type")
                        val rawViewBox = parseViewBox(enh?.attr("viewBox") ?: ch.attr("viewBox"))
                        val svgD = if (ch.local == "path") ch.attr("d") else enh?.attr("enhanced-path")?.let { enhancedPathToSvgD(it, enh) }
                        val cornerR = parseLengthToCm(ch.attr("corner-radius")) ?: 0f

                        if (paras.isNotEmpty() || tbl != null || imgPath != null || fillCol != null || grad != null || fImg != null || strokeCol != null || ch.local == "line" || ch.local == "connector") {
                            out.add(OdpElement(
                                kind = ch.local,
                                rectCm = rect,
                                paragraphs = paras,
                                table = tbl,
                                imagePath = imgPath,
                                fillColor = fillCol,
                                gradient = grad,
                                fillImagePath = fImg,
                                opacity = st.opacity,
                                strokeColor = strokeCol,
                                strokeWidthCm = st.strokeWidthCm ?: 0.035f,
                                strokeDashed = st.strokeDashed,
                                markerEnd = st.markerEnd,
                                markerStart = st.markerStart,
                                cornerRadiusCm = cornerR,
                                customType = customType,
                                svgPathD = svgD,
                                pointsStr = ch.attr("points"),
                                viewBox = rawViewBox,
                                transformMatrix = matArr,
                                vAlign = st.vAlign,
                                padCm = floatArrayOf(st.padLeftCm ?: 0.22f, st.padTopCm ?: 0.12f, st.padRightCm ?: 0.22f, st.padBottomCm ?: 0.12f),
                                presClass = presClass
                            ))
                        }
                    }
                }
            }
        }

        val masters = HashMap<String, MasterPageDef>()
        stylesRoot?.child("master-styles")?.childrenNamed("master-page")?.forEach { mp ->
            val nm = mp.attr("name") ?: return@forEach
            val els = ArrayList<OdpElement>()
            parseElements(mp, null, true, els, null)
            masters[nm] = MasterPageDef(nm, mp.attr("page-layout-name"), mp.attr("style-name"), els)
        }

        var pageWidthCm = 28.0f
        var pageHeightCm = 15.75f
        pageLayouts.values.firstOrNull()?.let { (w, h) -> pageWidthCm = w; pageHeightCm = h }

        val presNode = contentRoot.child("body")?.child("presentation") ?: contentRoot.child("body")?.child("drawing") ?: return null
        val slides = ArrayList<OdpSlide>()
        for (page in presNode.childrenNamed("page")) {
            if (slides.size >= 2000) break
            val master = page.attr("master-page-name")?.let { masters[it] } ?: masters.values.firstOrNull()
            if (slides.isEmpty()) {
                master?.pageLayoutName?.let { pageLayouts[it] }?.let { (w, h) -> pageWidthCm = w; pageHeightCm = h }
            }
            val masterBgStyle = master?.drawStyleName?.let { resolveStyle(it, "drawing-page") } ?: OdpStyle()
            val pageBgStyle = mergeStyles(masterBgStyle, resolveStyle(page.attr("style-name"), "drawing-page"))
            val bgNone = pageBgStyle.fillMode.equals("none", ignoreCase = true)
            val bgColor = if (bgNone) 0xFFFFFFFF.toInt() else pageBgStyle.fillColor ?: 0xFFFFFFFF.toInt()
            val bgGrad = if (!bgNone && pageBgStyle.fillMode.equals("gradient", ignoreCase = true)) pageBgStyle.fillGradientName?.let { gradients[it] } else null
            val bgImg = if (!bgNone && pageBgStyle.fillMode.equals("bitmap", ignoreCase = true)) pageBgStyle.fillImageName?.let { fillImages[it] } else null

            val els = ArrayList<OdpElement>()
            val summary = StringBuilder()
            parseElements(page, null, false, els, summary)

            val notesSb = StringBuilder()
            page.child("notes")?.let { notesNode ->
                val noteEls = ArrayList<OdpElement>()
                parseElements(notesNode, null, false, noteEls, notesSb)
            }

            slides.add(OdpSlide(
                name = page.attr("name") ?: "Slide ${slides.size + 1}",
                slideNumber = slides.size + 1,
                backgroundColor = bgColor,
                backgroundGradient = bgGrad,
                backgroundImagePath = bgImg,
                masterElements = master?.elements ?: emptyList(),
                elements = els,
                notesText = notesSb.toString().trim(),
                plainText = summary.toString().trim()
            ))
        }
        return OdpDocument(pageWidthCm, pageHeightCm, slides, mediaMap)
    }

    fun drawSlide(
        canvas: Canvas,
        doc: OdpDocument,
        slide: OdpSlide,
        slideRect: RectF,
        bitmapCache: MutableMap<String, Bitmap?>
    ) {
        val sx = slideRect.width() / doc.pageWidthCm.coerceAtLeast(1f)
        val sy = slideRect.height() / doc.pageHeightCm.coerceAtLeast(1f)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = slide.backgroundColor }
        slide.backgroundGradient?.let { bgPaint.shader = buildGradientShader(it, slideRect) }
        canvas.drawRect(slideRect, bgPaint)
        slide.backgroundImagePath?.let { p ->
            decodeCachedBitmap(p, doc.mediaMap, bitmapCache)?.let { bmp ->
                canvas.drawBitmap(bmp, null, slideRect, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        canvas.save()
        canvas.clipRect(slideRect)
        for (el in slide.masterElements) drawElement(canvas, doc, slide, el, slideRect, sx, sy, fillPaint, strokePaint, bitmapCache)
        for (el in slide.elements) drawElement(canvas, doc, slide, el, slideRect, sx, sy, fillPaint, strokePaint, bitmapCache)
        canvas.restore()
    }

    private fun drawElement(
        canvas: Canvas,
        doc: OdpDocument,
        slide: OdpSlide,
        el: OdpElement,
        slideRect: RectF,
        sx: Float,
        sy: Float,
        fillPaint: Paint,
        strokePaint: Paint,
        bitmapCache: MutableMap<String, Bitmap?>
    ) {
        val rc = el.rectCm ?: OdpBox(1.2f, 1.2f, doc.pageWidthCm - 1.2f, doc.pageHeightCm - 1.2f)
        val dst = RectF(
            slideRect.left + rc.left * sx,
            slideRect.top + rc.top * sy,
            slideRect.left + rc.right * sx,
            slideRect.top + rc.bottom * sy
        )

        canvas.save()
        el.transformMatrix?.let { arr ->
            val m = Matrix()
            val scaled = arr.copyOf()
            scaled[Matrix.MTRANS_X] *= sx
            scaled[Matrix.MTRANS_Y] *= sy
            m.setValues(scaled)
            canvas.translate(slideRect.left, slideRect.top)
            canvas.concat(m)
            canvas.translate(-slideRect.left, -slideRect.top)
        }

        val alpha = (el.opacity.coerceIn(0f, 1f) * 255f).toInt()
        val strokePx = (el.strokeWidthCm * sx).coerceIn(1f, 16f)
        strokePaint.strokeWidth = strokePx
        strokePaint.pathEffect = if (el.strokeDashed) DashPathEffect(floatArrayOf(strokePx * 4f, strokePx * 2.5f), 0f) else null

        if (el.kind == "line" || el.kind == "connector") {
            strokePaint.color = el.strokeColor ?: Color.DKGRAY
            strokePaint.alpha = alpha
            canvas.drawLine(dst.left, dst.top, dst.right, dst.bottom, strokePaint)
            if (el.markerEnd) drawArrowHead(canvas, dst.left, dst.top, dst.right, dst.bottom, strokePx * 3.2f, strokePaint.color)
            if (el.markerStart) drawArrowHead(canvas, dst.right, dst.bottom, dst.left, dst.top, strokePx * 3.2f, strokePaint.color)
            strokePaint.pathEffect = null
            canvas.restore()
            return
        }

        val hasFill = el.fillColor != null || el.gradient != null || el.fillImagePath != null
        val shapePath = buildShapePath(el, dst, sx)

        if (hasFill) {
            fillPaint.shader = null
            fillPaint.color = el.fillColor ?: Color.TRANSPARENT
            el.gradient?.let { fillPaint.shader = buildGradientShader(it, dst) }
            fillPaint.alpha = alpha
            if (shapePath != null) {
                canvas.drawPath(shapePath, fillPaint)
            } else if (el.kind == "ellipse" || el.kind == "circle" || el.customType == "ellipse") {
                canvas.drawOval(dst, fillPaint)
            } else {
                val r = el.cornerRadiusCm * sx
                if (r > 0.5f) canvas.drawRoundRect(dst, r, r, fillPaint) else canvas.drawRect(dst, fillPaint)
            }
            fillPaint.shader = null
            el.fillImagePath?.let { p ->
                decodeCachedBitmap(p, doc.mediaMap, bitmapCache)?.let { bmp ->
                    canvas.save()
                    if (shapePath != null) canvas.clipPath(shapePath) else canvas.clipRect(dst)
                    canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                    canvas.restore()
                }
            }
        }

        if (el.strokeColor != null) {
            strokePaint.color = el.strokeColor
            strokePaint.alpha = alpha
            if (shapePath != null) {
                canvas.drawPath(shapePath, strokePaint)
            } else if (el.kind == "ellipse" || el.kind == "circle" || el.customType == "ellipse") {
                canvas.drawOval(dst, strokePaint)
            } else {
                val r = el.cornerRadiusCm * sx
                if (r > 0.5f) canvas.drawRoundRect(dst, r, r, strokePaint) else canvas.drawRect(dst, strokePaint)
            }
        }
        strokePaint.pathEffect = null

        el.imagePath?.let { p ->
            decodeCachedBitmap(p, doc.mediaMap, bitmapCache)?.let { bmp ->
                canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }

        el.table?.let { tbl ->
            drawTable(canvas, tbl, dst, sx, sy, slide.slideNumber, fillPaint, strokePaint)
        }

        if (el.paragraphs.isNotEmpty()) {
            drawParagraphs(canvas, el, dst, sx, sy, slide.slideNumber)
        }

        canvas.restore()
    }

    private fun drawTable(
        canvas: Canvas,
        tbl: OdpTable,
        dst: RectF,
        sx: Float,
        sy: Float,
        slideNumber: Int,
        fillPaint: Paint,
        strokePaint: Paint
    ) {
        val rCount = tbl.rows.size
        if (rCount == 0) return
        val cCount = maxOf(tbl.colWidthsCm.size, tbl.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
        val rawW = FloatArray(cCount) { tbl.colWidthsCm.getOrElse(it) { 1f }.coerceAtLeast(0.2f) }
        val sumW = rawW.sum().coerceAtLeast(0.1f)
        val colX = FloatArray(cCount + 1)
        colX[0] = dst.left
        for (c in 0 until cCount) colX[c + 1] = colX[c] + dst.width() * (rawW[c] / sumW)
        val rowH = dst.height() / rCount

        strokePaint.strokeWidth = maxOf(1f, 0.025f * sx)
        strokePaint.pathEffect = null

        for (r in 0 until rCount) {
            val row = tbl.rows[r]
            for (c in 0 until minOf(row.size, cCount)) {
                val cell = row[c]
                if (cell.covered) continue
                val cEnd = (c + cell.colSpan).coerceAtMost(cCount)
                val rEnd = (r + cell.rowSpan).coerceAtMost(rCount)
                val cr = RectF(colX[c], dst.top + r * rowH, colX[cEnd], dst.top + rEnd * rowH)
                val bg = cell.bgColor ?: if (r == 0) Color.rgb(230, 236, 246) else if (r % 2 == 1) Color.WHITE else Color.rgb(246, 248, 252)
                fillPaint.color = bg
                canvas.drawRect(cr, fillPaint)
                strokePaint.color = cell.borderColor ?: Color.rgb(175, 184, 200)
                canvas.drawRect(cr, strokePaint)
                if (cell.paragraphs.isNotEmpty()) {
                    val cellEl = OdpElement("table-cell", null, cell.paragraphs, vAlign = cell.vAlign, padCm = floatArrayOf(0.1f, 0.06f, 0.1f, 0.06f))
                    drawParagraphs(canvas, cellEl, cr, sx, sy, slideNumber, defaultPt = 13f)
                }
            }
        }
    }

    private fun drawParagraphs(
        canvas: Canvas,
        el: OdpElement,
        dst: RectF,
        sx: Float,
        sy: Float,
        slideNumber: Int,
        defaultPt: Float = 18f
    ) {
        val padL = el.padCm[0] * sx
        val padT = el.padCm[1] * sy
        val padR = el.padCm[2] * sx
        val padB = el.padCm[3] * sy
        val innerW = (dst.width() - padL - padR).toInt().coerceAtLeast(24)
        val ptToPx = (sy / 28.3465f).coerceAtLeast(0.4f)
        val basePt = when (el.presClass) {
            "title" -> 36f
            "subtitle" -> 22f
            "outline" -> 20f
            else -> defaultPt
        }
        val baseColor = Color.rgb(28, 32, 42)
        val layouts = ArrayList<Triple<StaticLayout, Float, Float>>()
        var totalH = 0f

        for (para in el.paragraphs) {
            val ssb = SpannableStringBuilder()
            val indentPx = (para.marginLeftCm * sx).coerceIn(0f, innerW * 0.45f)
            val paraW = (innerW - indentPx.toInt()).coerceAtLeast(20)
            para.bullet?.let { b ->
                val start = ssb.length
                ssb.append(b)
                val firstRun = para.runs.firstOrNull()
                val sz = ((firstRun?.fontSizePt ?: basePt) * ptToPx).toInt().coerceIn(8, 220)
                ssb.setSpan(AbsoluteSizeSpan(sz, false), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ForegroundColorSpan(firstRun?.color ?: baseColor), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for (r in para.runs) {
                val txt = if (r.isPageNumber) slideNumber.toString() else r.text
                if (txt.isEmpty()) continue
                val start = ssb.length
                ssb.append(txt)
                val end = ssb.length
                val sz = ((r.fontSizePt ?: basePt) * ptToPx).toInt().coerceIn(8, 220)
                ssb.setSpan(AbsoluteSizeSpan(sz, false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(ForegroundColorSpan(r.color ?: baseColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val styleBits = (if (r.bold || el.presClass == "title") Typeface.BOLD else 0) or (if (r.italic) Typeface.ITALIC else 0)
                if (styleBits != 0) ssb.setSpan(StyleSpan(styleBits), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.underline) ssb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.strike) ssb.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                r.fontFamily?.let { fam -> ssb.setSpan(TypefaceSpan(fam), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            if (ssb.isEmpty()) continue
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = baseColor
                textSize = basePt * ptToPx
                typeface = OfficeFonts.resolve("Liberation Sans", false, false) ?: Typeface.SANS_SERIF
            }
            val alignment = when (para.align?.lowercase(Locale.ROOT)) {
                "center" -> Layout.Alignment.ALIGN_CENTER
                "end", "right" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> if (el.kind == "custom-shape" || el.kind == "rect" || el.kind == "ellipse") Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
            }
            val layout = StaticLayout.Builder.obtain(ssb, 0, ssb.length, tp, paraW)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()
            val beforePx = para.spaceBeforeCm * sy
            val afterPx = para.spaceAfterCm * sy
            totalH += beforePx + layout.height + afterPx
            layouts.add(Triple(layout, indentPx, beforePx))
        }

        val availH = (dst.height() - padT - padB).coerceAtLeast(1f)
        val startY = when (el.vAlign?.lowercase(Locale.ROOT)) {
            "middle", "center" -> dst.top + padT + ((availH - totalH) / 2f).coerceAtLeast(0f)
            "bottom" -> dst.top + padT + (availH - totalH).coerceAtLeast(0f)
            else -> if (el.kind == "custom-shape" || el.kind == "rect" || el.kind == "ellipse") dst.top + padT + ((availH - totalH) / 2f).coerceAtLeast(0f) else dst.top + padT
        }

        canvas.save()
        canvas.clipRect(dst)
        var y = startY
        for ((idx, entry) in layouts.withIndex()) {
            val (layout, indentPx, beforePx) = entry
            y += beforePx
            canvas.save()
            canvas.translate(dst.left + padL + indentPx, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + el.paragraphs.getOrNull(idx)?.spaceAfterCm.let { (it ?: 0f) * sy }
        }
        canvas.restore()
    }

    private fun buildShapePath(el: OdpElement, dst: RectF, sx: Float): Path? {
        if (!el.svgPathD.isNullOrBlank()) {
            val vb = el.viewBox ?: OdpBox(0f, 0f, 21600f, 21600f)
            parseSvgPath(el.svgPathD, vb, dst)?.let { return it }
        }
        if ((el.kind == "polygon" || el.kind == "polyline") && !el.pointsStr.isNullOrBlank()) {
            val vb = el.viewBox ?: OdpBox(0f, 0f, 21600f, 21600f)
            parsePointsPath(el.pointsStr, vb, dst, el.kind == "polygon")?.let { return it }
        }
        val t = el.customType?.lowercase(Locale.ROOT) ?: return null
        val p = Path()
        val l = dst.left; val top = dst.top; val r = dst.right; val b = dst.bottom
        val w = dst.width(); val h = dst.height(); val cx = dst.centerX(); val cy = dst.centerY()
        return when (t) {
            "round-rectangle" -> { val rad = minOf(w, h) * 0.16f; p.addRoundRect(dst, rad, rad, Path.Direction.CW); p }
            "diamond" -> { p.moveTo(cx, top); p.lineTo(r, cy); p.lineTo(cx, b); p.lineTo(l, cy); p.close(); p }
            "isosceles-triangle" -> { p.moveTo(cx, top); p.lineTo(r, b); p.lineTo(l, b); p.close(); p }
            "right-triangle" -> { p.moveTo(l, top); p.lineTo(r, b); p.lineTo(l, b); p.close(); p }
            "parallelogram" -> { val off = w * 0.2f; p.moveTo(l + off, top); p.lineTo(r, top); p.lineTo(r - off, b); p.lineTo(l, b); p.close(); p }
            "trapezoid" -> { val off = w * 0.2f; p.moveTo(l + off, top); p.lineTo(r - off, top); p.lineTo(r, b); p.lineTo(l, b); p.close(); p }
            "pentagon" -> regularPolygonPath(cx, cy, w / 2f, h / 2f, 5, -90.0)
            "hexagon" -> regularPolygonPath(cx, cy, w / 2f, h / 2f, 6, 0.0)
            "octagon" -> regularPolygonPath(cx, cy, w / 2f, h / 2f, 8, 22.5)
            "star5" -> starPath(cx, cy, w / 2f, h / 2f, 5)
            "right-arrow" -> {
                val head = w * 0.3f; val stem = h * 0.25f
                p.moveTo(l, top + stem); p.lineTo(r - head, top + stem); p.lineTo(r - head, top); p.lineTo(r, cy)
                p.lineTo(r - head, b); p.lineTo(r - head, b - stem); p.lineTo(l, b - stem); p.close(); p
            }
            "left-arrow" -> {
                val head = w * 0.3f; val stem = h * 0.25f
                p.moveTo(r, top + stem); p.lineTo(l + head, top + stem); p.lineTo(l + head, top); p.lineTo(l, cy)
                p.lineTo(l + head, b); p.lineTo(l + head, b - stem); p.lineTo(r, b - stem); p.close(); p
            }
            "up-arrow" -> {
                val head = h * 0.3f; val stem = w * 0.25f
                p.moveTo(l + stem, b); p.lineTo(l + stem, top + head); p.lineTo(l, top + head); p.lineTo(cx, top)
                p.lineTo(r, top + head); p.lineTo(r - stem, top + head); p.lineTo(r - stem, b); p.close(); p
            }
            "down-arrow" -> {
                val head = h * 0.3f; val stem = w * 0.25f
                p.moveTo(l + stem, top); p.lineTo(l + stem, b - head); p.lineTo(l, b - head); p.lineTo(cx, b)
                p.lineTo(r, b - head); p.lineTo(r - stem, b - head); p.lineTo(r - stem, top); p.close(); p
            }
            "chevron" -> {
                val notch = w * 0.25f
                p.moveTo(l, top); p.lineTo(r - notch, top); p.lineTo(r, cy); p.lineTo(r - notch, b); p.lineTo(l, b); p.lineTo(l + notch, cy); p.close(); p
            }
            else -> null
        }
    }

    private fun regularPolygonPath(cx: Float, cy: Float, rx: Float, ry: Float, sides: Int, startDeg: Double): Path {
        val p = Path()
        for (i in 0 until sides) {
            val a = Math.toRadians(startDeg + i * 360.0 / sides)
            val x = cx + (rx * Math.cos(a)).toFloat()
            val y = cy + (ry * Math.sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    private fun starPath(cx: Float, cy: Float, rx: Float, ry: Float, points: Int): Path {
        val p = Path()
        for (i in 0 until points * 2) {
            val a = Math.toRadians(-90.0 + i * 180.0 / points)
            val scale = if (i % 2 == 0) 1f else 0.42f
            val x = cx + (rx * scale * Math.cos(a)).toFloat()
            val y = cy + (ry * scale * Math.sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    private fun drawArrowHead(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, size: Float, color: Int) {
        val ang = Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val a1 = ang + Math.toRadians(150.0)
        val a2 = ang - Math.toRadians(150.0)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x1 + (size * Math.cos(a1)).toFloat(), y1 + (size * Math.sin(a1)).toFloat())
            lineTo(x1 + (size * Math.cos(a2)).toFloat(), y1 + (size * Math.sin(a2)).toFloat())
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color })
    }

    private fun buildGradientShader(g: OdpGradient, rect: RectF): Shader {
        if (g.style == "radial" || g.style == "ellipsoid") {
            return RadialGradient(rect.centerX(), rect.centerY(), maxOf(rect.width(), rect.height()) * 0.6f, g.startColor, g.endColor, Shader.TileMode.CLAMP)
        }
        val rad = Math.toRadians(g.angleDeg.toDouble())
        val dx = Math.sin(rad).toFloat() * rect.width() * 0.5f
        val dy = Math.cos(rad).toFloat() * rect.height() * 0.5f
        return LinearGradient(rect.centerX() - dx, rect.centerY() - dy, rect.centerX() + dx, rect.centerY() + dy, g.startColor, g.endColor, Shader.TileMode.CLAMP)
    }

    private fun decodeCachedBitmap(path: String, media: Map<String, ByteArray>, cache: MutableMap<String, Bitmap?>): Bitmap? {
        cache[path]?.let { if (!it.isRecycled) return it }
        if (path in cache) return null
        val bytes = media[path] ?: return null
        val bmp = try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                var sample = 1
                while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } else EmbeddedImageScanner.decodeMetafileOrSvgBitmap(bytes)
        } catch (_: Throwable) {
            null
        }
        cache[path] = bmp
        return bmp
    }

    private fun parseXmlTree(bytes: ByteArray): XmlNode? {
        return try {
            val xpp = EbookTextSession.createSafeXmlParser()
            xpp.setInput(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8))
            val stack = ArrayList<XmlNode>()
            var root: XmlNode? = null
            var ev = xpp.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val local = xpp.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                        val cnt = xpp.attributeCount
                        val attrs = if (cnt > 0) {
                            val m = HashMap<String, String>(cnt * 2)
                            for (i in 0 until cnt) {
                                val an = xpp.getAttributeName(i)?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: continue
                                m[an] = xpp.getAttributeValue(i) ?: ""
                            }
                            m
                        } else emptyMap()
                        val node = XmlNode(local, attrs)
                        if (stack.isNotEmpty()) {
                            stack.last().children.add(node)
                            stack.last().textParts.add(node)
                        } else root = node
                        stack.add(node)
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        val t = xpp.text
                        if (stack.isNotEmpty() && t != null && t.isNotEmpty()) {
                            stack.last().textParts.add(t)
                        }
                    }
                    XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                ev = xpp.next()
            }
            root
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildOdpStyle(props: Map<String, String>, fontFaces: Map<String, String>): OdpStyle {
        val op = props["opacity"]?.trim()?.removeSuffix("%")?.toFloatOrNull()?.let { (it / 100f).coerceIn(0f, 1f) } ?: 1f
        val bold = props["font-weight"]?.let { it.equals("bold", true) || (it.toIntOrNull() ?: 0) >= 600 }
        val italic = props["font-style"]?.let { it.equals("italic", true) || it.equals("oblique", true) }
        val underline = props["text-underline-style"]?.let { !it.equals("none", true) }
        val strike = props["text-line-through-style"]?.let { !it.equals("none", true) }
        val font = (props["font-name"]?.let { fontFaces[it] ?: it } ?: props["font-family"])?.trim()?.trim('\'', '"')
        return OdpStyle(
            fillMode = props["fill"],
            fillColor = parseColor(props["fill-color"] ?: props["background-color"]),
            fillGradientName = props["fill-gradient-name"],
            fillImageName = props["fill-image-name"],
            opacity = op,
            strokeMode = props["stroke"],
            strokeColor = parseColor(props["stroke-color"]),
            strokeWidthCm = parseLengthToCm(props["stroke-width"]),
            strokeDashed = props["stroke"].equals("dash", true) || props.containsKey("stroke-dash"),
            markerEnd = !props["marker-end"].isNullOrBlank(),
            markerStart = !props["marker-start"].isNullOrBlank(),
            textColor = parseColor(props["color"]),
            fontSizePt = parseFontSizePt(props["font-size"]),
            fontFamily = font,
            isBold = bold,
            isItalic = italic,
            isUnderline = underline,
            isStrike = strike,
            textAlign = props["text-align"],
            vAlign = props["textarea-vertical-align"] ?: props["vertical-align"],
            padLeftCm = parseLengthToCm(props["padding-left"] ?: props["padding"]),
            padRightCm = parseLengthToCm(props["padding-right"] ?: props["padding"]),
            padTopCm = parseLengthToCm(props["padding-top"] ?: props["padding"]),
            padBottomCm = parseLengthToCm(props["padding-bottom"] ?: props["padding"]),
            marginLeftCm = parseLengthToCm(props["margin-left"]),
            marginTopCm = parseLengthToCm(props["margin-top"]),
            marginBottomCm = parseLengthToCm(props["margin-bottom"]),
            bgColor = parseColor(props["background-color"]),
            borderAll = parseSimpleBorder(props["border"]),
            borderTop = parseSimpleBorder(props["border-top"]),
            borderBottom = parseSimpleBorder(props["border-bottom"]),
            borderLeft = parseSimpleBorder(props["border-left"]),
            borderRight = parseSimpleBorder(props["border-right"]),
            colWidthCm = parseLengthToCm(props["column-width"]),
            rowHeightCm = parseLengthToCm(props["row-height"])
        )
    }

    private fun parseSimpleBorder(raw: String?): Pair<Int, Float>? {
        if (raw.isNullOrBlank() || raw.contains("none", ignoreCase = true)) return null
        val parts = raw.trim().split(Regex("\\s+"))
        var col = 0xFFA0A8B8.toInt()
        var w = 0.03f
        for (p in parts) {
            if (p.startsWith("#")) parseColor(p)?.let { col = it }
            else parseLengthToCm(p)?.let { w = it }
        }
        return col to w
    }

    private fun parseColor(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (!s.startsWith("#")) return null
        val hex = s.substring(1)
        return when (hex.length) {
            6 -> hex.toIntOrNull(16)?.let { (0xFF shl 24) or it }
            8 -> hex.toLongOrNull(16)?.toInt()
            3 -> {
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                (0xFF shl 24) or ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
            }
            else -> null
        }
    }

    private fun parseLengthToCm(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase(Locale.ROOT)
        return when {
            s.endsWith("cm") -> s.removeSuffix("cm").trim().toFloatOrNull()
            s.endsWith("mm") -> s.removeSuffix("mm").trim().toFloatOrNull()?.div(10f)
            s.endsWith("in") -> s.removeSuffix("in").trim().toFloatOrNull()?.times(2.54f)
            s.endsWith("pt") -> s.removeSuffix("pt").trim().toFloatOrNull()?.times(0.0352778f)
            s.endsWith("px") -> s.removeSuffix("px").trim().toFloatOrNull()?.times(0.026458f)
            else -> s.toFloatOrNull()
        }
    }

    private fun parseFontSizePt(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lowercase(Locale.ROOT)
        return when {
            s.endsWith("pt") -> s.removeSuffix("pt").trim().toFloatOrNull()
            s.endsWith("px") -> s.removeSuffix("px").trim().toFloatOrNull()?.times(0.75f)
            s.endsWith("cm") -> s.removeSuffix("cm").trim().toFloatOrNull()?.times(28.3465f)
            else -> null
        }
    }

    private fun parseViewBox(raw: String?): OdpBox? {
        if (raw.isNullOrBlank()) return null
        val nums = raw.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (nums.size < 4 || nums[2] <= 0f || nums[3] <= 0f) return null
        return OdpBox(nums[0], nums[1], nums[0] + nums[2], nums[1] + nums[3])
    }

    private fun mul3x3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
        return out
    }

    private fun combineTransform(parent: FloatArray?, attr: String?): FloatArray? {
        if (attr.isNullOrBlank()) return parent?.copyOf()
        var m = parent?.copyOf() ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val rx = Regex("""(translate|rotate|scale|skewX|skewY|matrix)\s*\(([^)]*)\)""")
        var matched = false
        for (match in rx.findAll(attr)) {
            matched = true
            val fn = match.groupValues[1]
            val args = match.groupValues[2].trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            when (fn) {
                "translate" -> {
                    val tx = parseLengthToCm(args.getOrNull(0)) ?: 0f
                    val ty = parseLengthToCm(args.getOrNull(1)) ?: 0f
                    m = mul3x3(m, floatArrayOf(1f, 0f, tx, 0f, 1f, ty, 0f, 0f, 1f))
                }
                "rotate" -> {
                    val rad = -(args.getOrNull(0)?.toDoubleOrNull() ?: 0.0)
                    val cs = Math.cos(rad).toFloat()
                    val sn = Math.sin(rad).toFloat()
                    m = mul3x3(m, floatArrayOf(cs, -sn, 0f, sn, cs, 0f, 0f, 0f, 1f))
                }
                "scale" -> {
                    val sx = args.getOrNull(0)?.toFloatOrNull() ?: 1f
                    val sy = args.getOrNull(1)?.toFloatOrNull() ?: sx
                    m = mul3x3(m, floatArrayOf(sx, 0f, 0f, 0f, sy, 0f, 0f, 0f, 1f))
                }
            }
        }
        return if (matched) m else parent?.copyOf()
    }

    private fun enhancedPathToSvgD(raw: String, enh: XmlNode): String? {
        val mods = enh.attr("modifiers")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() } ?: emptyList()
        val eqs = HashMap<String, Double>()
        for (eq in enh.childrenNamed("equation")) {
            val nm = eq.attr("name") ?: continue
            val f = eq.attr("formula") ?: continue
            evalOdfFormula(f, mods, eqs)?.let { eqs[nm] = it }
        }
        val tokens = Regex("""[A-Za-z]|[?$][A-Za-z0-9_]+|[-+]?(?:\d+\.?\d*|\.\d+)""").findAll(raw).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        fun num(tok: String): Double? = when {
            tok.startsWith("$") -> mods.getOrNull(tok.substring(1).toIntOrNull() ?: -1) ?: 0.0
            tok.startsWith("?") -> eqs[tok.substring(1)] ?: 0.0
            else -> tok.toDoubleOrNull()
        }
        while (i < tokens.size) {
            val tok = tokens[i++]
            when (tok) {
                "M", "L", "C", "Q" -> {
                    sb.append(tok).append(' ')
                    while (i < tokens.size && !tokens[i][0].isLetter()) {
                        sb.append(num(tokens[i++]) ?: 0.0).append(' ')
                    }
                }
                "Z" -> sb.append("Z ")
                "N", "F", "S" -> {}
                else -> {
                    while (i < tokens.size && !tokens[i][0].isLetter()) i++
                }
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun evalOdfFormula(f: String, mods: List<Double>, eqs: Map<String, Double>): Double? {
        val s = f.trim()
        s.toDoubleOrNull()?.let { return it }
        if (s.startsWith("$")) return mods.getOrNull(s.substring(1).toIntOrNull() ?: -1)
        if (s.startsWith("?")) return eqs[s.substring(1)]
        val parts = s.split(Regex("\\s+"))
        if (parts.size == 3) {
            val a = evalOdfFormula(parts[0], mods, eqs) ?: return null
            val b = evalOdfFormula(parts[2], mods, eqs) ?: return null
            return when (parts[1]) {
                "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> if (b != 0.0) a / b else 0.0; else -> null
            }
        }
        return null
    }

    private fun parseSvgPath(d: String, vb: OdpBox, dst: RectF): Path? {
        val tokens = Regex("""[MmLlHhVvCcQqZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(d).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        val path = Path()
        val sx = dst.width() / vb.width.coerceAtLeast(1f)
        val sy = dst.height() / vb.height.coerceAtLeast(1f)
        fun mx(x: Float) = dst.left + (x - vb.left) * sx
        fun my(y: Float) = dst.top + (y - vb.top) * sy
        var i = 0; var cmd = ' '; var cx = 0f; var cy = 0f
        fun nextF(): Float? = tokens.getOrNull(i)?.toFloatOrNull()?.also { i++ }
        var hasMove = false
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) { cmd = t[0]; i++ }
            when (cmd) {
                'M', 'm' -> {
                    val x = nextF() ?: break; val y = nextF() ?: break
                    cx = if (cmd == 'm') cx + x else x; cy = if (cmd == 'm') cy + y else y
                    path.moveTo(mx(cx), my(cy)); hasMove = true
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    val x = nextF() ?: break; val y = nextF() ?: break
                    cx = if (cmd == 'l') cx + x else x; cy = if (cmd == 'l') cy + y else y
                    path.lineTo(mx(cx), my(cy))
                }
                'H', 'h' -> {
                    val x = nextF() ?: break
                    cx = if (cmd == 'h') cx + x else x
                    path.lineTo(mx(cx), my(cy))
                }
                'V', 'v' -> {
                    val y = nextF() ?: break
                    cy = if (cmd == 'v') cy + y else y
                    path.lineTo(mx(cx), my(cy))
                }
                'C', 'c' -> {
                    val x1 = nextF() ?: break; val y1 = nextF() ?: break
                    val x2 = nextF() ?: break; val y2 = nextF() ?: break
                    val x3 = nextF() ?: break; val y3 = nextF() ?: break
                    if (cmd == 'c') {
                        path.cubicTo(mx(cx + x1), my(cy + y1), mx(cx + x2), my(cy + y2), mx(cx + x3), my(cy + y3))
                        cx += x3; cy += y3
                    } else {
                        path.cubicTo(mx(x1), my(y1), mx(x2), my(y2), mx(x3), my(y3))
                        cx = x3; cy = y3
                    }
                }
                'Q', 'q' -> {
                    val x1 = nextF() ?: break; val y1 = nextF() ?: break
                    val x2 = nextF() ?: break; val y2 = nextF() ?: break
                    if (cmd == 'q') {
                        path.quadTo(mx(cx + x1), my(cy + y1), mx(cx + x2), my(cy + y2))
                        cx += x2; cy += y2
                    } else {
                        path.quadTo(mx(x1), my(y1), mx(x2), my(y2))
                        cx = x2; cy = y2
                    }
                }
                'Z', 'z' -> path.close()
                else -> i++
            }
        }
        return if (hasMove) path else null
    }

    private fun parsePointsPath(pts: String, vb: OdpBox, dst: RectF, close: Boolean): Path? {
        val nums = pts.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (nums.size < 4) return null
        val sx = dst.width() / vb.width.coerceAtLeast(1f)
        val sy = dst.height() / vb.height.coerceAtLeast(1f)
        val p = Path()
        var idx = 0
        while (idx + 1 < nums.size) {
            val x = dst.left + (nums[idx] - vb.left) * sx
            val y = dst.top + (nums[idx + 1] - vb.top) * sy
            if (idx == 0) p.moveTo(x, y) else p.lineTo(x, y)
            idx += 2
        }
        if (close) p.close()
        return p
    }

    private fun formatListNumber(n: Int, fmt: String): String = when (fmt) {
        "a" -> if (n > 0) ('a' + (n - 1) % 26).toString() else n.toString()
        "A" -> if (n > 0) ('A' + (n - 1) % 26).toString() else n.toString()
        "i" -> toRoman(n).lowercase(Locale.ROOT)
        "I" -> toRoman(n)
        else -> n.toString()
    }

    private fun toRoman(n0: Int): String {
        if (n0 <= 0 || n0 >= 4000) return n0.toString()
        var n = n0
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder()
        for (i in vals.indices) while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i] }
        return sb.toString()
    }
}
