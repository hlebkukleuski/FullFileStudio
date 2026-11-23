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

package com.omnireader.core.engine.word

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.omnireader.core.engine.impl.EmbeddedImageScanner
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

internal class DocxReader(private val entries: Map<String, ByteArray>, private val maxImageDim: Int = 1600) {
    private class Node(val name: String, val attrs: Map<String, String>, val children: ArrayList<Node> = ArrayList(), var text: String = "") {
        fun attr(key: String): String? = attrs[key]
        fun child(name: String): Node? = children.firstOrNull { it.name == name }
        fun childrenNamed(name: String): List<Node> = children.filter { it.name == name }
    }

    private class Theme {
        var majorLatin = "Cambria"
        var minorLatin = "Calibri"
        var majorEa = ""
        var minorEa = ""
        var majorCs = ""
        var minorCs = ""
        val colors = HashMap<String, Int>()

        fun font(themeKey: String): String? = when (themeKey) {
            "majorHAnsi", "majorAscii" -> majorLatin
            "minorHAnsi", "minorAscii" -> minorLatin
            "majorEastAsia" -> majorEa.ifEmpty { majorLatin }
            "minorEastAsia" -> minorEa.ifEmpty { minorLatin }
            "majorBidi" -> majorCs.ifEmpty { majorLatin }
            "minorBidi" -> minorCs.ifEmpty { minorLatin }
            else -> null
        }

        fun color(name: String, tint: Int, shade: Int): Int? {
            val mapped = when (name) {
                "text1", "tx1" -> "dk1"
                "background1", "bg1" -> "lt1"
                "text2", "tx2" -> "dk2"
                "background2", "bg2" -> "lt2"
                "hyperlink" -> "hlink"
                "followedHyperlink" -> "folHlink"
                else -> name
            }
            val base = colors[mapped] ?: defaultThemeColor(mapped) ?: return null
            var r = (base ushr 16) and 0xFF
            var g = (base ushr 8) and 0xFF
            var b = base and 0xFF
            if (tint in 1..254) {
                r = (r * tint + 255 * (255 - tint)) / 255
                g = (g * tint + 255 * (255 - tint)) / 255
                b = (b * tint + 255 * (255 - tint)) / 255
            }
            if (shade in 1..254) {
                r = r * shade / 255
                g = g * shade / 255
                b = b * shade / 255
            }
            return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }

        private fun defaultThemeColor(k: String): Int? = when (k) {
            "dk1" -> 0xFF000000.toInt()
            "lt1" -> 0xFFFFFFFF.toInt()
            "dk2" -> 0xFF1F497D.toInt()
            "lt2" -> 0xFFEEECE1.toInt()
            "accent1" -> 0xFF4F81BD.toInt()
            "accent2" -> 0xFFC0504D.toInt()
            "accent3" -> 0xFF9BBB59.toInt()
            "accent4" -> 0xFF8064A2.toInt()
            "accent5" -> 0xFF4BACC6.toInt()
            "accent6" -> 0xFFF79646.toInt()
            "hlink" -> 0xFF0000FF.toInt()
            "folHlink" -> 0xFF800080.toInt()
            else -> null
        }
    }

    private class StyleDef(
        val id: String,
        val istd: Int,
        val type: String,
        val name: String,
        val basedOn: String?,
        val isDefault: Boolean,
        val pPr: Node?,
        val rPr: Node?,
        val tblPr: Node?,
        val trPr: Node?,
        val tcPr: Node?,
        val tblStylePr: Map<String, Node>
    )

    private class NumLvl(
        val start: Int,
        val fmt: String,
        val text: String,
        val jc: Int,
        val suff: Int,
        val restart: Int,
        val pPr: Node?,
        val rPr: Node?
    )

    private class AbstractNum(val id: Int, val levels: Array<NumLvl?>, val numStyleLink: String?, val styleLink: String?)
    private class NumDef(val id: Int, val abstractId: Int, val startOverrides: Map<Int, Int>, val lvlOverrides: Map<Int, NumLvl>)

    private class SectionBuild(val blocks: ArrayList<WBlock>, var sectNode: Node?)

    private val lowerMap: Map<String, ByteArray> = entries.entries.associate { it.key.trimStart('/').lowercase(Locale.ROOT) to it.value }
    private val fonts = ArrayList<String>()
    private val fontIndex = HashMap<String, Int>()
    private val theme = Theme()
    private val styles = LinkedHashMap<String, StyleDef>()
    private val defaultStyles = HashMap<String, StyleDef>()
    private var docDefaultPPr: Node? = null
    private var docDefaultRPr: Node? = null
    private val stylePapCache = HashMap<String, ParaProps>()
    private val styleChpCache = HashMap<String, CharProps>()
    private val styleNumCache = HashMap<String, Pair<Int, Int>?>()
    private val abstractNums = HashMap<Int, AbstractNum>()
    private val numDefs = HashMap<Int, NumDef>()
    private val numCounters = HashMap<Int, IntArray>()
    private val numSeen = HashMap<Int, BooleanArray>()
    private val footnoteMap = LinkedHashMap<String, Int>()
    private val endnoteMap = LinkedHashMap<String, Int>()
    private val commentMap = LinkedHashMap<String, Int>()
    private val footnotes = ArrayList<WNote>()
    private val endnotes = ArrayList<WNote>()
    private val comments = ArrayList<WComment>()
    private val plain = StringBuilder()

    private var facingPages = false
    private var defaultTab = 720
    private var htmlSpacing = true
    private var autoHyphen = false
    private var autoHyphenCaps = true
    private var hotZoneTw = 360
    private var consecHyphLim = 0
    private var docLid = 0x0409
    private var currentNoteLabel: String? = null
    private var mainDocPath = "word/document.xml"
    private var activeRels: Map<String, String> = emptyMap()
    private var mainRels: Map<String, String> = emptyMap()
    private var parseDepth = 0

    private fun entry(path: String): ByteArray? = lowerMap[normalizePath(path).lowercase(Locale.ROOT)]

    private fun internFont(name: String?): Int {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
        fontIndex[clean]?.let { return it }
        val idx = fonts.size
        fonts.add(clean)
        fontIndex[clean] = idx
        return idx
    }

    private fun parseXml(bytes: ByteArray?): Node? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val xpp = factory.newPullParser()
            xpp.setInput(ByteArrayInputStream(bytes), null)
            val stack = ArrayList<Node>()
            var root: Node? = null
            var ev = xpp.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val attrCount = xpp.attributeCount
                        val attrs = if (attrCount > 0) {
                            val m = HashMap<String, String>(attrCount * 2)
                            for (i in 0 until attrCount) {
                                val k = xpp.getAttributeName(i)
                                val p = xpp.getAttributePrefix(i)
                                val v = xpp.getAttributeValue(i) ?: ""
                                if (p != null && p.isNotEmpty()) m["$p:$k"] = v
                                if (k !in m) m[k] = v
                            }
                            m
                        } else emptyMap()
                        val node = Node(xpp.name ?: "", attrs)
                        if (stack.isNotEmpty()) stack.last().children.add(node) else root = node
                        stack.add(node)
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (stack.isNotEmpty()) {
                            val t = xpp.text
                            if (t != null && t.isNotEmpty()) {
                                val top = stack.last()
                                top.text = if (top.text.isEmpty()) t else top.text + t
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    }
                }
                ev = xpp.next()
            }
            root
        } catch (_: Throwable) {
            null
        }
    }

    private fun readRels(partPath: String): Map<String, String> {
        val norm = normalizePath(partPath)
        val slash = norm.lastIndexOf('/')
        val dir = if (slash >= 0) norm.substring(0, slash) else ""
        val file = if (slash >= 0) norm.substring(slash + 1) else norm
        val relsPath = if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
        val root = parseXml(entry(relsPath)) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (c in root.childrenNamed("Relationship")) {
            val id = c.attr("Id") ?: continue
            val target = c.attr("Target") ?: continue
            val mode = c.attr("TargetMode")
            if (mode.equals("External", ignoreCase = true)) continue
            val resolved = if (target.startsWith("/")) normalizePath(target.trimStart('/'))
            else normalizePath(if (dir.isEmpty()) target else "$dir/$target")
            out[id] = resolved
        }
        return out
    }

    private fun readRelsByType(partPath: String): Map<String, String> {
        val norm = normalizePath(partPath)
        val slash = norm.lastIndexOf('/')
        val dir = if (slash >= 0) norm.substring(0, slash) else ""
        val file = if (slash >= 0) norm.substring(slash + 1) else norm
        val relsPath = if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
        val root = parseXml(entry(relsPath)) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (c in root.childrenNamed("Relationship")) {
            val type = c.attr("Type") ?: continue
            val target = c.attr("Target") ?: continue
            val mode = c.attr("TargetMode")
            if (mode.equals("External", ignoreCase = true)) continue
            val resolved = if (target.startsWith("/")) normalizePath(target.trimStart('/'))
            else normalizePath(if (dir.isEmpty()) target else "$dir/$target")
            out[type.substringAfterLast('/')] = resolved
        }
        return out
    }

    private fun readTheme(path: String?) {
        val root = parseXml(entry(path ?: "word/theme/theme1.xml")) ?: return
        val elements = root.child("themeElements") ?: return
        elements.child("fontScheme")?.let { fs ->
            fs.child("majorFont")?.let { mf ->
                mf.child("latin")?.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { theme.majorLatin = it }
                mf.child("ea")?.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { theme.majorEa = it }
                mf.child("cs")?.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { theme.majorCs = it }
            }
            fs.child("minorFont")?.let { mf ->
                mf.child("latin")?.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { theme.minorLatin = it }
                mf.child("ea")?.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { theme.minorEa = it }
                mf.child("cs")?.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { theme.minorCs = it }
            }
        }
        elements.child("clrScheme")?.let { cs ->
            for (c in cs.children) {
                val hex = c.child("srgbClr")?.attr("val") ?: c.child("sysClr")?.attr("lastClr") ?: c.child("sysClr")?.attr("val")
                parseHexRgb(hex)?.let { theme.colors[c.name] = it }
            }
        }
    }

    private fun readSettings(path: String?) {
        val root = parseXml(entry(path ?: "word/settings.xml")) ?: return
        root.child("defaultTabStop")?.attr("val")?.toIntOrNull()?.takeIf { it in 1..31680 }?.let { defaultTab = it }
        if (root.child("evenAndOddHeaders") != null && boolVal(root.child("evenAndOddHeaders")!!)) facingPages = true
        root.child("autoHyphenation")?.let { autoHyphen = boolVal(it) }
        root.child("doNotHyphenateCaps")?.let { autoHyphenCaps = !boolVal(it) }
        root.child("hyphenationZone")?.attr("val")?.toIntOrNull()?.takeIf { it > 0 }?.let { hotZoneTw = it }
        root.child("consecutiveHyphenLimit")?.attr("val")?.toIntOrNull()?.takeIf { it >= 0 }?.let { consecHyphLim = it }
        root.child("compat")?.child("doNotUseHTMLParagraphAutoSpacing")?.let { if (boolVal(it)) htmlSpacing = false }
        root.child("themeFontLang")?.attr("val")?.let { langToLid(it).takeIf { l -> l != 0 }?.let { l -> docLid = l } }
    }

    private fun readStyles(path: String?) {
        val root = parseXml(entry(path ?: "word/styles.xml")) ?: return
        root.child("docDefaults")?.let { dd ->
            docDefaultRPr = dd.child("rPrDefault")?.child("rPr")
            docDefaultPPr = dd.child("pPrDefault")?.child("pPr")
        }
        var nextIstd = 1
        for (st in root.childrenNamed("style")) {
            val id = st.attr("styleId") ?: continue
            val type = st.attr("type") ?: "paragraph"
            val name = st.child("name")?.attr("val") ?: id
            val basedOn = st.child("basedOn")?.attr("val")
            val isDef = st.attr("default") == "1" || st.attr("default").equals("true", ignoreCase = true)
            val tblCond = HashMap<String, Node>()
            for (c in st.childrenNamed("tblStylePr")) {
                c.attr("type")?.let { tblCond[it] = c }
            }
            val istd = if (isDef && type == "paragraph") 0 else nextIstd++
            val def = StyleDef(id, istd, type, name, basedOn, isDef, st.child("pPr"), st.child("rPr"), st.child("tblPr"), st.child("trPr"), st.child("tcPr"), tblCond)
            styles[id] = def
            if (isDef && type !in defaultStyles) defaultStyles[type] = def
        }
    }

    private fun readNumbering(path: String?) {
        val root = parseXml(entry(path ?: "word/numbering.xml")) ?: return
        for (ab in root.childrenNamed("abstractNum")) {
            val id = ab.attr("abstractNumId")?.toIntOrNull() ?: continue
            val levels = arrayOfNulls<NumLvl>(9)
            for (lv in ab.childrenNamed("lvl")) {
                val ilvl = lv.attr("ilvl")?.toIntOrNull() ?: continue
                if (ilvl in 0..8) levels[ilvl] = parseNumLvl(lv)
            }
            val numStyleLink = ab.child("numStyleLink")?.attr("val")
            val styleLink = ab.child("styleLink")?.attr("val")
            abstractNums[id] = AbstractNum(id, levels, numStyleLink, styleLink)
        }
        for (nm in root.childrenNamed("num")) {
            val id = nm.attr("numId")?.toIntOrNull() ?: continue
            val abId = nm.child("abstractNumId")?.attr("val")?.toIntOrNull() ?: continue
            val startOv = HashMap<Int, Int>()
            val lvlOv = HashMap<Int, NumLvl>()
            for (ov in nm.childrenNamed("lvlOverride")) {
                val ilvl = ov.attr("ilvl")?.toIntOrNull() ?: continue
                ov.child("startOverride")?.attr("val")?.toIntOrNull()?.let { startOv[ilvl] = it }
                ov.child("lvl")?.let { lvlOv[ilvl] = parseNumLvl(it) }
            }
            numDefs[id] = NumDef(id, abId, startOv, lvlOv)
        }
    }

    private fun parseNumLvl(lv: Node): NumLvl {
        val start = lv.child("start")?.attr("val")?.toIntOrNull() ?: 1
        val fmt = lv.child("numFmt")?.attr("val") ?: "decimal"
        val text = lv.child("lvlText")?.attr("val") ?: ""
        val jc = when (lv.child("lvlJc")?.attr("val")) { "center" -> 1; "right", "end" -> 2; else -> 0 }
        val suff = when (lv.child("suff")?.attr("val")) { "space" -> 1; "nothing" -> 2; else -> 0 }
        val restart = lv.child("lvlRestart")?.attr("val")?.toIntOrNull() ?: -1
        return NumLvl(start, fmt, text, jc, suff, restart, lv.child("pPr"), lv.child("rPr"))
    }

    private fun resolveNumLvl(numId: Int, ilvl: Int): Pair<NumLvl?, Int> {
        val def = numDefs[numId] ?: return null to 1
        val ovLvl = def.lvlOverrides[ilvl]
        if (ovLvl != null) return ovLvl to (def.startOverrides[ilvl] ?: ovLvl.start)
        var ab = abstractNums[def.abstractId] ?: return null to 1
        if (ab.numStyleLink != null) {
            val st = styles[ab.numStyleLink]
            val linkedNumId = st?.pPr?.child("numPr")?.child("numId")?.attr("val")?.toIntOrNull()
            if (linkedNumId != null) {
                numDefs[linkedNumId]?.let { ld -> abstractNums[ld.abstractId]?.let { ab = it } }
            }
        }
        val lv = ab.levels.getOrNull(ilvl) ?: return null to 1
        return lv to (def.startOverrides[ilvl] ?: lv.start)
    }

    private fun readNotes(path: String?, endnote: Boolean) {
        if (path == null) return
        val root = parseXml(entry(path)) ?: return
        val savedRels = activeRels
        activeRels = readRels(path)
        val tag = if (endnote) "endnote" else "footnote"
        var auto = 0
        for (n in root.childrenNamed(tag)) {
            val type = n.attr("type")
            if (type == "separator" || type == "continuationSeparator") continue
            val id = n.attr("id") ?: continue
            auto++
            val label = if (endnote) roman(auto, false) else auto.toString()
            currentNoteLabel = label
            val savedPlain = plain.length
            val blocks = parseContainerBlocks(n, 0, null)
            plain.setLength(savedPlain)
            currentNoteLabel = null
            val list = if (endnote) endnotes else footnotes
            val map = if (endnote) endnoteMap else footnoteMap
            map[id] = list.size
            list.add(WNote(label, blocks))
        }
        activeRels = savedRels
    }

    private fun readComments(path: String?) {
        if (path == null) return
        val root = parseXml(entry(path)) ?: return
        for (c in root.childrenNamed("comment")) {
            val id = c.attr("id") ?: continue
            val author = c.attr("author")?.trim() ?: ""
            val initials = c.attr("initials")?.trim() ?: ""
            val sb = StringBuilder()
            fun collectText(node: Node) {
                for (ch in node.children) {
                    when (ch.name) {
                        "t" -> sb.append(ch.text)
                        "tab" -> sb.append(' ')
                        "br", "cr" -> sb.append('\n')
                        "p" -> {
                            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                            collectText(ch)
                        }
                        "del" -> {}
                        else -> collectText(ch)
                    }
                }
            }
            collectText(c)
            commentMap[id] = comments.size
            comments.add(WComment(author, initials, sb.toString().trim()))
        }
    }

    private fun rootChp(): CharProps {
        val c = CharProps()
        val minorIdx = internFont(theme.minorLatin)
        c.fontAscii = minorIdx
        c.fontOther = minorIdx
        c.fontEa = if (theme.minorEa.isNotEmpty()) internFont(theme.minorEa) else minorIdx
        c.fontBi = if (theme.minorCs.isNotEmpty()) internFont(theme.minorCs) else minorIdx
        c.size = 22
        c.sizeBi = 22
        c.lid = docLid
        docDefaultRPr?.let { applyRPr(c, it) }
        return c
    }

    private fun rootPap(): ParaProps {
        val p = ParaProps()
        docDefaultPPr?.let { applyPPr(p, it) }
        return p
    }

    private fun stylePap(styleId: String?, depth: Int = 0): ParaProps {
        val key = styleId ?: defaultStyles["paragraph"]?.id ?: return rootPap()
        stylePapCache[key]?.let { return it.copy() }
        val st = styles[key] ?: return rootPap()
        val base = if (st.basedOn != null && st.basedOn != key && depth < 16) stylePap(st.basedOn, depth + 1) else rootPap()
        base.istd = st.istd
        st.pPr?.let { applyPPr(base, it) }
        base.istd = st.istd
        stylePapCache[key] = base.copy()
        return base
    }

    private fun styleChp(styleId: String?, depth: Int = 0): CharProps {
        val key = styleId ?: defaultStyles["paragraph"]?.id ?: return rootChp()
        styleChpCache[key]?.let { return it.copy() }
        val st = styles[key] ?: return rootChp()
        val base = if (st.basedOn != null && st.basedOn != key && depth < 16) styleChp(st.basedOn, depth + 1) else rootChp()
        st.rPr?.let { applyRPr(base, it) }
        styleChpCache[key] = base.copy()
        return base
    }

    private fun styleNumPr(styleId: String?, depth: Int = 0): Pair<Int, Int>? {
        val key = styleId ?: return null
        if (key in styleNumCache) return styleNumCache[key]
        val st = styles[key] ?: return null
        var res = if (st.basedOn != null && st.basedOn != key && depth < 16) styleNumPr(st.basedOn, depth + 1) else null
        st.pPr?.child("numPr")?.let { np ->
            val nid = np.child("numId")?.attr("val")?.toIntOrNull()
            val ilvl = np.child("ilvl")?.attr("val")?.toIntOrNull()
            if (nid != null) res = nid to (ilvl ?: res?.second ?: 0)
            else if (ilvl != null && res != null) res = res!!.first to ilvl
        }
        styleNumCache[key] = res
        return res
    }

    private fun applyCharStyleChain(c: CharProps, styleId: String?, depth: Int = 0) {
        if (styleId == null || depth >= 16) return
        val st = styles[styleId] ?: return
        if (st.basedOn != null && st.basedOn != styleId) applyCharStyleChain(c, st.basedOn, depth + 1)
        st.rPr?.let { applyRPr(c, it) }
    }

    private fun applyRPr(c: CharProps, rPr: Node) {
        for (ch in rPr.children) {
            when (ch.name) {
                "rStyle" -> applyCharStyleChain(c, ch.attr("val"))
                "rFonts" -> {
                    val asc = ch.attr("ascii") ?: ch.attr("asciiTheme")?.let { theme.font(it) }
                    val hAnsi = ch.attr("hAnsi") ?: ch.attr("hAnsiTheme")?.let { theme.font(it) }
                    val ea = ch.attr("eastAsia") ?: ch.attr("eastAsiaTheme")?.let { theme.font(it) }
                    val cs = ch.attr("cs") ?: ch.attr("cstheme")?.let { theme.font(it) }
                    asc?.let { c.fontAscii = internFont(it) }
                    (hAnsi ?: asc)?.let { c.fontOther = internFont(it) }
                    ea?.let { c.fontEa = internFont(it) }
                    cs?.let { c.fontBi = internFont(it) }
                }
                "b" -> c.bold = boolVal(ch)
                "bCs" -> c.boldBi = boolVal(ch)
                "i" -> c.italic = boolVal(ch)
                "iCs" -> c.italicBi = boolVal(ch)
                "caps" -> c.caps = boolVal(ch)
                "smallCaps" -> c.smallCaps = boolVal(ch)
                "strike" -> c.strike = boolVal(ch)
                "dstrike" -> c.dstrike = boolVal(ch)
                "outline" -> c.outline = boolVal(ch)
                "shadow" -> c.shadow = boolVal(ch)
                "emboss" -> c.emboss = boolVal(ch)
                "imprint" -> c.imprint = boolVal(ch)
                "vanish", "webHidden" -> c.hidden = boolVal(ch)
                "sz" -> ch.attr("val")?.toIntOrNull()?.takeIf { it > 0 }?.let { c.size = it }
                "szCs" -> ch.attr("val")?.toIntOrNull()?.takeIf { it > 0 }?.let { c.sizeBi = it }
                "color" -> {
                    parseColorAttr(ch)?.let { c.color = it }
                }
                "highlight" -> {
                    c.highlight = parseHighlight(ch.attr("val"))
                }
                "u" -> {
                    c.underline = parseUnderline(ch.attr("val"))
                    parseColorAttr(ch, "color", "themeColor", "themeTint", "themeShade")?.let { c.underlineColor = it }
                }
                "shd" -> {
                    parseShading(ch)?.let { c.shading = it }
                }
                "bdr" -> {
                    c.border = parseBorder(ch)
                }
                "vertAlign" -> {
                    c.vertAlign = when (ch.attr("val")) { "superscript" -> 1; "subscript" -> 2; else -> 0 }
                }
                "position" -> ch.attr("val")?.toIntOrNull()?.let { c.position = it }
                "spacing" -> ch.attr("val")?.toIntOrNull()?.let { c.spacing = it }
                "w" -> ch.attr("val")?.toIntOrNull()?.takeIf { it in 1..600 }?.let { c.scale = it }
                "kern" -> ch.attr("val")?.toIntOrNull()?.let { c.kern = it }
                "rtl" -> {
                    val v = boolVal(ch)
                    c.rtl = v
                    c.complex = v
                }
                "lang" -> {
                    ch.attr("val")?.let { langToLid(it).takeIf { l -> l != 0 }?.let { l -> c.lid = l } }
                }
            }
        }
    }

    private fun applyPPr(p: ParaProps, pPr: Node) {
        for (ch in pPr.children) {
            when (ch.name) {
                "jc" -> p.jc = when (ch.attr("val")) {
                    "center" -> 1
                    "right", "end" -> 2
                    "both" -> 3
                    "distribute" -> 4
                    else -> 0
                }
                "ind" -> {
                    (ch.attr("left") ?: ch.attr("start"))?.toIntOrNull()?.let { p.indLeft = it }
                    (ch.attr("right") ?: ch.attr("end"))?.toIntOrNull()?.let { p.indRight = it }
                    ch.attr("firstLine")?.toIntOrNull()?.let { p.indFirst = it }
                    ch.attr("hanging")?.toIntOrNull()?.let { p.indFirst = -it }
                }
                "spacing" -> {
                    ch.attr("before")?.toIntOrNull()?.let { p.spBefore = it; p.beforeAuto = false }
                    ch.attr("after")?.toIntOrNull()?.let { p.spAfter = it; p.afterAuto = false }
                    ch.attr("beforeAutospacing")?.let { p.beforeAuto = boolStr(it) }
                    ch.attr("afterAutospacing")?.let { p.afterAuto = boolStr(it) }
                    ch.attr("beforeLines")?.toIntOrNull()?.let { if (ch.attr("before") == null) p.spBefore = it * 240 / 100 }
                    ch.attr("afterLines")?.toIntOrNull()?.let { if (ch.attr("after") == null) p.spAfter = it * 240 / 100 }
                    val line = ch.attr("line")?.toIntOrNull()
                    val rule = ch.attr("lineRule")
                    if (line != null) {
                        when (rule) {
                            "exact" -> { p.lineMult = false; p.lineValue = -Math.abs(line) }
                            "atLeast" -> { p.lineMult = false; p.lineValue = Math.abs(line) }
                            else -> { p.lineMult = true; p.lineValue = line }
                        }
                    }
                }
                "keepNext" -> p.keepNext = boolVal(ch)
                "keepLines" -> p.keep = boolVal(ch)
                "pageBreakBefore" -> p.pageBreakBefore = boolVal(ch)
                "widowControl" -> p.widowControl = boolVal(ch)
                "contextualSpacing" -> p.contextualSpacing = boolVal(ch)
                "suppressAutoHyphens" -> p.noAutoHyph = boolVal(ch)
                "bidi" -> p.bidi = boolVal(ch)
                "outlineLvl" -> ch.attr("val")?.toIntOrNull()?.let { p.outlineLvl = it }
                "tabs" -> {
                    for (t in ch.childrenNamed("tab")) {
                        val pos = t.attr("pos")?.toIntOrNull() ?: continue
                        val v = t.attr("val")
                        if (v == "clear") {
                            p.tabs.removeAll { it.pos == pos }
                            continue
                        }
                        val jc = when (v) { "center" -> 1; "right", "end" -> 2; "decimal" -> 3; "bar" -> 4; else -> 0 }
                        val leader = when (t.attr("leader")) {
                            "dot" -> 1; "hyphen" -> 2; "underscore" -> 3; "heavy" -> 4; "middleDot" -> 5; else -> 0
                        }
                        p.tabs.removeAll { it.pos == pos }
                        p.tabs.add(WTab(pos, jc, leader))
                    }
                    p.tabs.sortBy { it.pos }
                }
                "pBdr" -> {
                    ch.child("top")?.let { p.borders[0] = parseBorder(it) }
                    ch.child("left")?.let { p.borders[1] = parseBorder(it) }
                    ch.child("bottom")?.let { p.borders[2] = parseBorder(it) }
                    ch.child("right")?.let { p.borders[3] = parseBorder(it) }
                    ch.child("between")?.let { p.borders[4] = parseBorder(it) }
                }
                "shd" -> parseShading(ch)?.let { p.shading = it }
                "framePr" -> {
                    p.frame = true
                    p.dropCap = when (ch.attr("dropCap")) { "drop" -> 1; "margin" -> 2; else -> 0 }
                    ch.attr("lines")?.toIntOrNull()?.let { p.dropLines = it }
                    ch.attr("w")?.toIntOrNull()?.let { p.frameW = it }
                    ch.attr("h")?.toIntOrNull()?.let { p.frameH = it }
                    p.frameMinH = ch.attr("hRule") == "atLeast"
                    ch.attr("hSpace")?.toIntOrNull()?.let { p.frameFromX = it }
                    ch.attr("vSpace")?.toIntOrNull()?.let { p.frameFromY = it }
                    val pcH = when (ch.attr("hAnchor")) { "margin" -> 1; "page" -> 2; else -> 0 }
                    val pcV = when (ch.attr("vAnchor")) { "margin" -> 0; "page" -> 1; else -> 2 }
                    p.framePc = (pcH shl 6) or (pcV shl 4)
                    p.frameWrap = when (ch.attr("wrap")) { "none", "notBeside" -> 1; else -> 0 }
                    p.frameX = when (ch.attr("xAlign")) {
                        "left" -> 0; "center" -> -4; "right" -> -8; "inside" -> -12; "outside" -> -16
                        else -> ch.attr("x")?.toIntOrNull()?.let { if (it == 0) 0 else it + 1 } ?: 0
                    }
                    p.frameY = when (ch.attr("yAlign")) {
                        "top" -> -4; "center" -> -8; "bottom" -> -12; "inside" -> -16; "outside" -> -20
                        else -> ch.attr("y")?.toIntOrNull()?.let { if (it == 0) 0 else it + 1 } ?: 0
                    }
                }
            }
        }
    }

    private fun parseBorder(n: Node): WBorder {
        val v = n.attr("val") ?: "none"
        val type = when (v) {
            "none", "nil" -> 0
            "double" -> 3
            "hairline" -> 5
            "dotted" -> 6
            "dashed", "dashSmallGap" -> 7
            "dotDash" -> 8
            "dotDotDash" -> 9
            "triple" -> 10
            else -> 1
        }
        val sz = n.attr("sz")?.toIntOrNull() ?: if (type != 0) 4 else 0
        val space = n.attr("space")?.toIntOrNull() ?: 0
        val col = parseColorAttr(n, "color", "themeColor", "themeTint", "themeShade") ?: WShading.AUTO
        val shadow = n.attr("shadow")?.let { boolStr(it) } ?: false
        return WBorder(type, sz, col, space, shadow)
    }

    private fun parseShading(n: Node): WShading? {
        val v = n.attr("val") ?: "clear"
        if (v == "nil") return null
        val pat = when (v) {
            "clear" -> 0
            "solid" -> 1
            "pct5" -> 2; "pct10" -> 3; "pct20" -> 4; "pct25" -> 5; "pct30" -> 6; "pct40" -> 7
            "pct50" -> 8; "pct60" -> 9; "pct70" -> 10; "pct75" -> 11; "pct80" -> 12; "pct90" -> 13
            "pct12" -> 35; "pct15" -> 37; "pct35" -> 42; "pct37" -> 43; "pct45" -> 45; "pct55" -> 48
            "pct62" -> 50; "pct65" -> 51; "pct85" -> 57; "pct87" -> 58; "pct95" -> 60
            else -> 14
        }
        val fore = parseColorAttr(n, "color", "themeColor", "themeTint", "themeShade") ?: WShading.AUTO
        val back = parseColorAttr(n, "fill", "themeFill", "themeFillTint", "themeFillShade") ?: WShading.AUTO
        if (pat == 0 && back == WShading.AUTO) return null
        return WShading(fore, back, pat)
    }

    private fun parseColorAttr(n: Node, colAttr: String = "val", themeAttr: String = "themeColor", tintAttr: String = "themeTint", shadeAttr: String = "themeShade"): Int? {
        val tc = n.attr(themeAttr)
        val raw = n.attr(colAttr)
        if (raw != null && raw.equals("auto", ignoreCase = true)) return WShading.AUTO
        if (tc != null) {
            val tint = n.attr(tintAttr)?.toIntOrNull(16) ?: 255
            val shade = n.attr(shadeAttr)?.toIntOrNull(16) ?: 255
            theme.color(tc, tint, shade)?.let { return it }
        }
        return parseHexRgb(raw)
    }

    private fun parseHexRgb(s: String?): Int? {
        if (s == null) return null
        val clean = s.trim().trimStart('#')
        if (clean.length != 6) return null
        val v = clean.toIntOrNull(16) ?: return null
        return (0xFF shl 24) or v
    }

    private fun parseHighlight(v: String?): Int = when (v) {
        "black" -> 1; "blue" -> 2; "cyan" -> 3; "green" -> 4; "magenta" -> 5; "red" -> 6; "yellow" -> 7; "white" -> 8
        "darkBlue" -> 9; "darkCyan" -> 10; "darkGreen" -> 11; "darkMagenta" -> 12; "darkRed" -> 13; "darkYellow" -> 14
        "darkGray" -> 15; "lightGray" -> 16; else -> 0
    }

    private fun parseUnderline(v: String?): Int = when (v) {
        null, "none" -> 0
        "single" -> 1; "words" -> 2; "double" -> 3; "dotted" -> 4; "thick" -> 6; "dash" -> 7
        "dotDash" -> 9; "dotDotDash" -> 10; "wave" -> 11; "dottedHeavy" -> 20; "dashedHeavy", "dashLong", "dashLongHeavy" -> 23
        "dashDotHeavy" -> 25; "dashDotDotHeavy" -> 26; "wavyHeavy" -> 27; "wavyDouble" -> 43
        else -> 1
    }

    private fun parseContainerBlocks(container: Node, tableDepth: Int, sectionsOut: ArrayList<SectionBuild>?): List<WBlock> {
        val out = ArrayList<WBlock>()
        var curBlocks = out
        var pendingDeletedPara: WParagraph? = null
        fun emitBlock(b: WBlock) {
            if (b is WParagraph) {
                val prev = pendingDeletedPara
                if (prev != null) {
                    pendingDeletedPara = null
                    val merged = ArrayList<WInline>(prev.inlines.size + b.inlines.size)
                    merged.addAll(prev.inlines)
                    merged.addAll(b.inlines)
                    val combined = WParagraph(b.props, b.markProps, merged)
                    combined.listLabel = prev.listLabel ?: b.listLabel
                    combined.listLabelProps = prev.listLabelProps ?: b.listLabelProps
                    combined.listFollow = if (prev.listLabel != null) prev.listFollow else b.listFollow
                    combined.listJc = if (prev.listLabel != null) prev.listJc else b.listJc
                    combined.styleName = b.styleName
                    curBlocks.add(combined)
                    return
                }
                curBlocks.add(b)
            } else {
                pendingDeletedPara?.let { curBlocks.add(it); pendingDeletedPara = null }
                curBlocks.add(b)
            }
        }
        fun walk(node: Node) {
            for (ch in node.children) {
                when (ch.name) {
                    "p" -> {
                        val (para, sectNode, markDeleted) = parseParagraph(ch, tableDepth)
                        if (markDeleted && sectNode == null) {
                            val prev = pendingDeletedPara
                            pendingDeletedPara = if (prev != null) {
                                WParagraph(para.props, para.markProps, prev.inlines + para.inlines).also {
                                    it.listLabel = prev.listLabel; it.listLabelProps = prev.listLabelProps; it.listFollow = prev.listFollow; it.listJc = prev.listJc
                                }
                            } else para
                        } else {
                            emitBlock(para)
                            if (sectNode != null && sectionsOut != null) {
                                sectionsOut.add(SectionBuild(curBlocks, sectNode))
                                curBlocks = ArrayList()
                            }
                        }
                    }
                    "tbl" -> {
                        if (parseDepth < 12) {
                            parseDepth++
                            val tbl = parseTable(ch, tableDepth + 1)
                            parseDepth--
                            if (tbl.rows.isNotEmpty()) emitBlock(tbl)
                        }
                    }
                    "sdt" -> ch.child("sdtContent")?.let { walk(it) }
                    "customXml", "ins" -> walk(ch)
                    "del" -> {}
                    "sectPr" -> {
                        if (sectionsOut != null) {
                            pendingDeletedPara?.let { curBlocks.add(it); pendingDeletedPara = null }
                            sectionsOut.add(SectionBuild(curBlocks, ch))
                            curBlocks = ArrayList()
                        }
                    }
                }
            }
        }
        walk(container)
        pendingDeletedPara?.let { curBlocks.add(it) }
        if (sectionsOut != null && curBlocks.isNotEmpty()) {
            sectionsOut.add(SectionBuild(curBlocks, null))
        }
        return out
    }

    private class FieldState {
        var stage = 0
        val instr = StringBuilder()
        var emitted = false
    }

    private fun parseParagraph(pNode: Node, tableDepth: Int): Triple<WParagraph, Node?, Boolean> {
        val pPr = pNode.child("pPr")
        val styleId = pPr?.child("pStyle")?.attr("val")
        val pap = stylePap(styleId)
        val baseChp = styleChp(styleId)
        var numPair = styleNumPr(styleId)
        pPr?.child("numPr")?.let { np ->
            val nid = np.child("numId")?.attr("val")?.toIntOrNull()
            val ilvl = np.child("ilvl")?.attr("val")?.toIntOrNull()
            if (nid != null) numPair = nid to (ilvl ?: numPair?.second ?: 0)
            else if (ilvl != null && numPair != null) numPair = numPair!!.first to ilvl
        }
        var numLvl: NumLvl? = null
        var numStart = 1
        var numId = 0
        var ilvl = 0
        if (numPair != null && numPair!!.first > 0) {
            numId = numPair!!.first
            ilvl = numPair!!.second.coerceIn(0, 8)
            val resolved = resolveNumLvl(numId, ilvl)
            numLvl = resolved.first
            numStart = resolved.second
            numLvl?.pPr?.let { applyPPr(pap, it) }
        }
        if (pPr != null) applyPPr(pap, pPr)
        if (tableDepth > 0) {
            pap.inTable = true
            pap.itap = tableDepth
        }
        val markChp = baseChp.copy()
        var markDeleted = false
        pPr?.child("rPr")?.let { mr ->
            if (mr.child("del") != null) markDeleted = true
            applyRPr(markChp, mr)
        }
        val sectNode = pPr?.child("sectPr")
        val inlines = ArrayList<WInline>()
        val fields = ArrayList<FieldState>()

        fun emitText(s: String, c: CharProps) {
            if (s.isEmpty() || c.hidden) return
            val last = inlines.lastOrNull()
            if (last is WInline.Text && last.props.sameLook(c)) {
                inlines[inlines.size - 1] = WInline.Text(last.text + s, last.props)
            } else {
                inlines.add(WInline.Text(s, c))
            }
            if (plain.length < 4_000_000) plain.append(s)
        }

        fun parseFieldInstruction(instrRaw: String, c: CharProps): Boolean {
            val trimmed = instrRaw.trim()
            val up = trimmed.uppercase(Locale.ROOT)
            val kind = when {
                up == "PAGE" || up.startsWith("PAGE ") -> WInline.FIELD_PAGE
                up == "NUMPAGES" || up.startsWith("NUMPAGES ") -> WInline.FIELD_NUMPAGES
                up == "SECTIONPAGES" || up.startsWith("SECTIONPAGES ") -> WInline.FIELD_SECTIONPAGES
                else -> 0
            }
            if (kind == 0) return false
            val fmt = when {
                trimmed.contains("\\* ROMAN") -> 1
                trimmed.contains("\\* roman") -> 2
                trimmed.contains("\\* ALPHABETIC") -> 3
                trimmed.contains("\\* alphabetic") -> 4
                else -> 0
            }
            inlines.add(WInline.Field(kind, c, fmt))
            return true
        }

        fun parseRun(rNode: Node) {
            val c = baseChp.copy()
            rNode.child("rPr")?.let { applyRPr(c, it) }
            val fontName = fonts.getOrNull(c.fontAscii)
            for (ch in rNode.children) {
                when (ch.name) {
                    "rPr" -> {}
                    "fldChar" -> {
                        when (ch.attr("fldCharType")) {
                            "begin" -> fields.add(FieldState())
                            "separate" -> fields.lastOrNull()?.let { fs ->
                                fs.stage = 1
                                if (parseFieldInstruction(fs.instr.toString(), c)) fs.emitted = true
                            }
                            "end" -> if (fields.isNotEmpty()) {
                                val fs = fields.removeAt(fields.size - 1)
                                if (fs.stage == 0 && !fs.emitted) parseFieldInstruction(fs.instr.toString(), c)
                            }
                        }
                    }
                    "instrText" -> fields.lastOrNull()?.let { if (it.stage == 0) it.instr.append(ch.text) }
                    "t" -> {
                        val fs = fields.lastOrNull()
                        if (fs != null && (fs.stage == 0 || fs.emitted)) continue
                        emitText(mapSymbolText(ch.text, fontName), c)
                    }
                    "tab" -> {
                        val fs = fields.lastOrNull()
                        if (fs != null && (fs.stage == 0 || fs.emitted)) continue
                        emitText("\t", c)
                    }
                    "br" -> {
                        val fs = fields.lastOrNull()
                        if (fs != null && (fs.stage == 0 || fs.emitted)) continue
                        when (ch.attr("type")) {
                            "page" -> emitText("\u000C", c)
                            "column" -> emitText("\u000E", c)
                            else -> emitText("\n", c)
                        }
                    }
                    "cr" -> emitText("\n", c)
                    "noBreakHyphen" -> emitText("\u2011", c)
                    "softHyphen" -> emitText("\u00AD", c)
                    "sym" -> {
                        val f = ch.attr("font")
                        val code = ch.attr("char")?.toIntOrNull(16) ?: 0
                        if (code != 0) emitText(mapSymbol(code, f).toString(), c)
                    }
                    "pgNum" -> inlines.add(WInline.Field(WInline.FIELD_PAGE, c, 0))
                    "footnoteRef", "endnoteRef" -> {
                        currentNoteLabel?.takeIf { it.isNotEmpty() }?.let { emitText(it, c) }
                    }
                    "footnoteReference" -> {
                        val id = ch.attr("id")
                        val idx = id?.let { footnoteMap[it] } ?: -1
                        val lbl = if (idx >= 0) footnotes[idx].label else ""
                        if (lbl.isNotEmpty() || idx >= 0) inlines.add(WInline.FootnoteRef(lbl, c, idx, false))
                    }
                    "endnoteReference" -> {
                        val id = ch.attr("id")
                        val idx = id?.let { endnoteMap[it] } ?: -1
                        val lbl = if (idx >= 0) endnotes[idx].label else ""
                        if (lbl.isNotEmpty() || idx >= 0) inlines.add(WInline.FootnoteRef(lbl, c, idx, true))
                    }
                    "commentReference" -> {
                        val id = ch.attr("id")
                        val idx = id?.let { commentMap[it] } ?: -1
                        if (idx >= 0) inlines.add(WInline.CommentRef(idx, c))
                    }
                    "drawing" -> parseDrawing(ch, c, inlines)
                    "pict" -> parseVmlPict(ch, c, inlines)
                    "object" -> parseVmlPict(ch, c, inlines)
                    "AlternateContent" -> {
                        val choice = ch.child("Choice")
                        val fallback = ch.child("Fallback")
                        val before = inlines.size
                        if (choice != null) {
                            for (sub in choice.children) {
                                when (sub.name) {
                                    "drawing" -> parseDrawing(sub, c, inlines)
                                    "pict" -> parseVmlPict(sub, c, inlines)
                                }
                            }
                        }
                        if (inlines.size == before && fallback != null) {
                            for (sub in fallback.children) {
                                when (sub.name) {
                                    "drawing" -> parseDrawing(sub, c, inlines)
                                    "pict" -> parseVmlPict(sub, c, inlines)
                                }
                            }
                        }
                    }
                }
            }
        }

        fun walkInlines(node: Node) {
            for (ch in node.children) {
                when (ch.name) {
                    "r" -> parseRun(ch)
                    "hyperlink", "smartTag", "ins", "customXml" -> walkInlines(ch)
                    "sdt" -> ch.child("sdtContent")?.let { walkInlines(it) }
                    "fldSimple" -> {
                        val instr = ch.attr("instr") ?: ""
                        if (!parseFieldInstruction(instr, baseChp)) walkInlines(ch)
                    }
                    "oMath", "oMathPara" -> {
                        val mtxt = extractOmmlText(ch)
                        if (mtxt.isNotEmpty()) {
                            val mc = baseChp.copy().apply { italic = true }
                            emitText(mtxt, mc)
                        }
                    }
                    "del" -> {}
                    "AlternateContent" -> {
                        val before = inlines.size
                        ch.child("Choice")?.let { walkInlines(it) }
                        if (inlines.size == before) ch.child("Fallback")?.let { walkInlines(it) }
                    }
                }
            }
        }
        walkInlines(pNode)
        if (plain.length < 4_000_000) plain.append('\n')

        val wp = WParagraph(pap, markChp, inlines)
        wp.styleName = styleId?.let { styles[it]?.name } ?: defaultStyles["paragraph"]?.name
        if (numLvl != null && numId > 0) {
            applyListLabel(wp, numId, ilvl, numLvl, numStart, baseChp)
        }
        return Triple(wp, sectNode, markDeleted)
    }

    private fun applyListLabel(wp: WParagraph, numId: Int, ilvl: Int, lvl: NumLvl, start: Int, baseChp: CharProps) {
        val counts = numCounters.getOrPut(numId) { IntArray(9) { Int.MIN_VALUE } }
        val seen = numSeen.getOrPut(numId) { BooleanArray(9) }
        if (!seen[ilvl]) {
            counts[ilvl] = start
            seen[ilvl] = true
        } else {
            counts[ilvl]++
        }
        for (k in ilvl + 1 until 9) {
            val childLvl = resolveNumLvl(numId, k).first
            val rst = childLvl?.restart ?: -1
            if (rst != 0) {
                seen[k] = false
                counts[k] = Int.MIN_VALUE
            }
        }
        val lblChp = baseChp.copy()
        lvl.rPr?.let { applyRPr(lblChp, it) }
        val fontName = fonts.getOrNull(lblChp.fontAscii)
        val raw = if (lvl.fmt == "bullet") {
            mapSymbolText(lvl.text.ifEmpty { "•" }, fontName)
        } else if (lvl.fmt == "none") {
            ""
        } else {
            val sb = StringBuilder()
            var i = 0
            while (i < lvl.text.length) {
                if (lvl.text[i] == '%' && i + 1 < lvl.text.length && lvl.text[i + 1] in '1'..'9') {
                    val refLvl = lvl.text[i + 1] - '1'
                    val refInfo = resolveNumLvl(numId, refLvl)
                    val v = if (seen[refLvl]) counts[refLvl] else refInfo.second
                    sb.append(formatNum(v, refInfo.first?.fmt ?: lvl.fmt))
                    i += 2
                } else {
                    sb.append(lvl.text[i])
                    i++
                }
            }
            sb.toString()
        }
        if (raw.isNotEmpty()) {
            wp.listLabel = raw
            wp.listLabelProps = lblChp
            wp.listFollow = lvl.suff
            wp.listJc = lvl.jc
        }
    }

    private fun formatNum(n: Int, fmt: String): String = when (fmt) {
        "upperRoman" -> roman(n, true)
        "lowerRoman" -> roman(n, false)
        "upperLetter" -> alpha(n, true)
        "lowerLetter" -> alpha(n, false)
        "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
        "ordinal" -> "$n" + when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        "russianLower" -> russianAlpha(n, false)
        "russianUpper" -> russianAlpha(n, true)
        "none" -> ""
        else -> n.toString()
    }

    private fun parseDrawing(drawing: Node, c: CharProps, inlines: ArrayList<WInline>) {
        for (child in drawing.children) {
            when (child.name) {
                "inline" -> {
                    val ext = child.child("extent")
                    val cx = ext?.attr("cx")?.toLongOrNull() ?: 0L
                    val cy = ext?.attr("cy")?.toLongOrNull() ?: 0L
                    val wTw = emuToTwips(cx)
                    val hTw = emuToTwips(cy)
                    val graphicData = child.child("graphic")?.child("graphicData") ?: continue
                    val img = extractGraphicImage(graphicData, wTw, hTw)
                    if (img != null) {
                        inlines.add(WInline.Picture(img, c))
                    } else {
                        val fl = extractGraphicShape(graphicData, 0, 0, wTw, hTw, 2, 2, 1, false)
                        if (fl != null) inlines.add(WInline.Anchor(fl, c))
                    }
                }
                "anchor" -> {
                    val ext = child.child("extent")
                    val cx = ext?.attr("cx")?.toLongOrNull() ?: 0L
                    val cy = ext?.attr("cy")?.toLongOrNull() ?: 0L
                    val wTw = emuToTwips(cx)
                    val hTw = emuToTwips(cy)
                    if (wTw <= 0 || hTw <= 0) continue
                    val behind = child.attr("behindDoc") == "1" || child.attr("behindDoc").equals("true", ignoreCase = true)
                    val posH = findPosNode(child, "positionH")
                    val posV = findPosNode(child, "positionV")
                    val relX = when (posH?.attr("relativeFrom")) { "page" -> 1; "margin", "leftMargin", "rightMargin" -> 0; else -> 2 }
                    val relY = when (posV?.attr("relativeFrom")) { "page" -> 1; "margin", "topMargin", "bottomMargin" -> 0; else -> 2 }
                    val leftTw = posH?.child("posOffset")?.text?.trim()?.toLongOrNull()?.let { emuToTwips(it) }
                        ?: posH?.child("pctPosHOffset")?.text?.trim()?.toLongOrNull()?.let { (12240L * it / 100000L).toInt() }
                        ?: when (posH?.child("align")?.text?.trim()) { "center" -> (9360 - wTw) / 2; "right" -> 9360 - wTw; else -> 0 }
                    val topTw = posV?.child("posOffset")?.text?.trim()?.toLongOrNull()?.let { emuToTwips(it) }
                        ?: posV?.child("pctPosVOffset")?.text?.trim()?.toLongOrNull()?.let { (15840L * it / 100000L).toInt() }
                        ?: 0
                    val wrap = when {
                        child.child("wrapTopAndBottom") != null -> 1
                        child.child("wrapSquare") != null -> 2
                        child.child("wrapTight") != null -> 4
                        child.child("wrapThrough") != null -> 5
                        else -> 3
                    }
                    val graphicData = child.child("graphic")?.child("graphicData") ?: continue
                    val img = extractGraphicImage(graphicData, wTw, hTw)
                    if (img != null) {
                        inlines.add(WInline.Anchor(WFloating(img, emptyList(), leftTw, topTw, leftTw + wTw, topTw + hTw, relX, relY, wrap, 0, behind, null, null), c))
                    } else {
                        val fl = extractGraphicShape(graphicData, leftTw, topTw, leftTw + wTw, topTw + hTw, relX, relY, wrap, behind)
                        if (fl != null) inlines.add(WInline.Anchor(fl, c))
                    }
                }
            }
        }
    }

    private fun findPosNode(anchor: Node, tag: String): Node? {
        anchor.child(tag)?.let { return it }
        for (ac in anchor.childrenNamed("AlternateContent")) {
            ac.child("Fallback")?.child(tag)?.let { return it }
            ac.child("Choice")?.child(tag)?.let { return it }
        }
        return null
    }

    private fun extractGraphicImage(graphicData: Node, wTw: Int, hTw: Int): WImage? {
        val pic = findFirst(graphicData, "pic") ?: return null
        val blipFill = pic.child("blipFill") ?: return null
        val blip = blipFill.child("blip") ?: return null
        val relId = blip.attr("r:embed") ?: blip.attr("embed") ?: blip.attr("r:link") ?: return null
        val target = activeRels[relId] ?: return null
        val bytes = entry(target) ?: return null
        val crop = blipFill.child("srcRect")?.let { sr ->
            val l = (sr.attr("l")?.toIntOrNull() ?: 0) / 100000f
            val t = (sr.attr("t")?.toIntOrNull() ?: 0) / 100000f
            val r = (sr.attr("r")?.toIntOrNull() ?: 0) / 100000f
            val b = (sr.attr("b")?.toIntOrNull() ?: 0) / 100000f
            if (l == 0f && t == 0f && r == 0f && b == 0f) null else floatArrayOf(l, t, r, b)
        }
        val tw = wTw.coerceAtLeast(20)
        val th = hTw.coerceAtLeast(20)
        return WImage(tw, th) { decodeImageBytes(bytes, crop) }
    }

    private fun extractGraphicShape(graphicData: Node, l: Int, t: Int, r: Int, b: Int, relX: Int, relY: Int, wrap: Int, behind: Boolean): WFloating? {
        val wsp = findFirst(graphicData, "wsp")
        if (wsp != null) {
            return buildWspFloating(wsp, l, t, r, b, relX, relY, wrap, behind)
        }
        val wgp = findFirst(graphicData, "wgp")
        if (wgp != null && parseDepth < 8) {
            val kids = ArrayList<WFloating>()
            val grpXfrm = wgp.child("grpSpPr")?.child("xfrm")
            val chOffX = grpXfrm?.child("chOff")?.attr("x")?.toLongOrNull() ?: 0L
            val chOffY = grpXfrm?.child("chOff")?.attr("y")?.toLongOrNull() ?: 0L
            val chExtX = grpXfrm?.child("chExt")?.attr("cx")?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
            val chExtY = grpXfrm?.child("chExt")?.attr("cy")?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
            for (k in wgp.childrenNamed("wsp")) {
                val xfrm = k.child("spPr")?.child("xfrm")
                val ox = xfrm?.child("off")?.attr("x")?.toLongOrNull() ?: chOffX
                val oy = xfrm?.child("off")?.attr("y")?.toLongOrNull() ?: chOffY
                val ex = xfrm?.child("ext")?.attr("cx")?.toLongOrNull() ?: chExtX
                val ey = xfrm?.child("ext")?.attr("cy")?.toLongOrNull() ?: chExtY
                val kl = l + ((ox - chOffX).toDouble() / chExtX * (r - l)).toInt()
                val kt = t + ((oy - chOffY).toDouble() / chExtY * (b - t)).toInt()
                val kr = kl + (ex.toDouble() / chExtX * (r - l)).toInt()
                val kb = kt + (ey.toDouble() / chExtY * (b - t)).toInt()
                buildWspFloating(k, kl, kt, kr, kb, relX, relY, wrap, behind)?.let { kids.add(it) }
            }
            if (kids.isNotEmpty()) return WFloating(null, emptyList(), l, t, r, b, relX, relY, wrap, 0, behind, null, null, kids)
        }
        return null
    }

    private fun buildWspFloating(wsp: Node, l: Int, t: Int, r: Int, b: Int, relX: Int, relY: Int, wrap: Int, behind: Boolean): WFloating? {
        val spPr = wsp.child("spPr")
        val fill = spPr?.child("solidFill")?.let { parseDmlColor(it) }
        val line = spPr?.child("ln")?.let { ln ->
            if (ln.child("noFill") != null) null
            else {
                val lc = ln.child("solidFill")?.let { parseDmlColor(it) } ?: 0xFF000000.toInt()
                val w8 = ((ln.attr("w")?.toLongOrNull() ?: 9525L) * 8L / 12700L).toInt().coerceAtLeast(1)
                WBorder(1, w8, lc, 0)
            }
        }
        val txbxContent = wsp.child("txbx")?.child("txbxContent")
        val blocks = if (txbxContent != null && parseDepth < 6) {
            parseDepth++
            val savedPlain = plain.length
            val bs = parseContainerBlocks(txbxContent, 0, null)
            plain.setLength(savedPlain)
            parseDepth--
            bs
        } else emptyList()
        if (fill == null && line == null && blocks.isEmpty()) return null
        val bodyPr = wsp.child("bodyPr")
        val ins = intArrayOf(
            emuToTwips(bodyPr?.attr("lIns")?.toLongOrNull() ?: 91440L),
            emuToTwips(bodyPr?.attr("tIns")?.toLongOrNull() ?: 45720L),
            emuToTwips(bodyPr?.attr("rIns")?.toLongOrNull() ?: 91440L),
            emuToTwips(bodyPr?.attr("bIns")?.toLongOrNull() ?: 45720L)
        )
        return WFloating(null, blocks, l, t, r, b, relX, relY, wrap, 0, behind, fill, line, emptyList(), ins)
    }

    private fun parseDmlColor(parent: Node): Int? {
        parent.child("srgbClr")?.attr("val")?.let { return parseHexRgb(it) }
        parent.child("schemeClr")?.let { sc ->
            val name = sc.attr("val") ?: return null
            return theme.color(name, 255, 255)
        }
        return null
    }

    private fun parseVmlPict(pict: Node, c: CharProps, inlines: ArrayList<WInline>) {
        fun walkVml(n: Node) {
            for (ch in n.children) {
                when (ch.name) {
                    "rect", "shape", "roundrect", "oval", "line" -> {
                        val isHr = ch.attr("o:hr") == "t" || ch.attr("hr") == "t"
                        val style = parseCssStyle(ch.attr("style"))
                        val wTw = parseCssLenTwips(style["width"])
                        val hTw = parseCssLenTwips(style["height"])
                        if (isHr) {
                            val pct = (ch.attr("o:hrpct") ?: ch.attr("hrpct"))?.toIntOrNull() ?: 1000
                            val align = when (ch.attr("o:hralign") ?: ch.attr("hralign")) { "left" -> 0; "right" -> 2; else -> 1 }
                            val noShade = (ch.attr("o:hrnoshade") ?: ch.attr("hrnoshade")) == "t"
                            val col = parseHexRgb(ch.attr("fillcolor")) ?: 0xFFA0A0A0.toInt()
                            inlines.add(WInline.Picture(WImage(wTw.coerceAtLeast(200), hTw.coerceAtLeast(20), 2, pct, align, noShade, col) { null }, c))
                            continue
                        }
                        val imgData = ch.child("imagedata")
                        if (imgData != null) {
                            val relId = imgData.attr("r:id") ?: imgData.attr("id") ?: imgData.attr("o:relid") ?: imgData.attr("relid")
                            val target = relId?.let { activeRels[it] }
                            val bytes = target?.let { entry(it) }
                            val tw = wTw.coerceAtLeast(400)
                            val th = hTw.coerceAtLeast(400)
                            val img = if (bytes != null) WImage(tw, th) { decodeImageBytes(bytes, null) } else WImage(tw, th, 1) { null }
                            inlines.add(WInline.Picture(img, c))
                            continue
                        }
                        val txbx = ch.child("textbox")?.child("txbxContent")
                        if (txbx != null && parseDepth < 6 && wTw > 0 && hTw > 0) {
                            parseDepth++
                            val savedPlain = plain.length
                            val blocks = parseContainerBlocks(txbx, 0, null)
                            plain.setLength(savedPlain)
                            parseDepth--
                            val leftTw = parseCssLenTwips(style["margin-left"] ?: style["left"])
                            val topTw = parseCssLenTwips(style["margin-top"] ?: style["top"])
                            val fill = if (ch.attr("filled") == "f") null else parseHexRgb(ch.attr("fillcolor"))
                            val line = if (ch.attr("stroked") == "f") null else WBorder(1, 6, parseHexRgb(ch.attr("strokecolor")) ?: 0xFF000000.toInt(), 0)
                            inlines.add(WInline.Anchor(WFloating(null, blocks, leftTw, topTw, leftTw + wTw, topTw + hTw, 2, 2, 2, 0, false, fill, line), c))
                        }
                    }
                    "group" -> walkVml(ch)
                }
            }
        }
        walkVml(pict)
    }

    private fun parseCssStyle(s: String?): Map<String, String> {
        if (s.isNullOrBlank()) return emptyMap()
        val m = HashMap<String, String>()
        for (part in s.split(';')) {
            val idx = part.indexOf(':')
            if (idx > 0) m[part.substring(0, idx).trim().lowercase(Locale.ROOT)] = part.substring(idx + 1).trim()
        }
        return m
    }

    private fun parseCssLenTwips(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        val clean = s.trim().lowercase(Locale.ROOT)
        return when {
            clean.endsWith("pt") -> ((clean.dropLast(2).toFloatOrNull() ?: 0f) * 20f).toInt()
            clean.endsWith("in") -> ((clean.dropLast(2).toFloatOrNull() ?: 0f) * 1440f).toInt()
            clean.endsWith("cm") -> ((clean.dropLast(2).toFloatOrNull() ?: 0f) * 566.93f).toInt()
            clean.endsWith("mm") -> ((clean.dropLast(2).toFloatOrNull() ?: 0f) * 56.693f).toInt()
            clean.endsWith("px") -> ((clean.dropLast(2).toFloatOrNull() ?: 0f) * 15f).toInt()
            else -> ((clean.toFloatOrNull() ?: 0f) * 20f).toInt()
        }
    }

    private fun decodeImageBytes(bytes: ByteArray, crop: FloatArray?): Bitmap? {
        var bmp: Bitmap? = try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                var sample = 1
                while (opts.outWidth / sample > maxImageDim || opts.outHeight / sample > maxImageDim) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } else null
        } catch (_: Throwable) {
            null
        }
        if (bmp == null) {
            bmp = try { EmbeddedImageScanner.decodeMetafileOrSvgBitmap(bytes, 0, bytes.size) } catch (_: Throwable) { null }
        }
        if (bmp != null && crop != null) {
            val x = (crop[0] * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = (crop[1] * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val r = (bmp.width - crop[2] * bmp.width).toInt().coerceIn(x + 1, bmp.width)
            val bt = (bmp.height - crop[3] * bmp.height).toInt().coerceIn(y + 1, bmp.height)
            bmp = try { Bitmap.createBitmap(bmp, x, y, r - x, bt - y) } catch (_: Throwable) { bmp }
        }
        return bmp
    }

    private fun parseTable(tblNode: Node, tableDepth: Int): WTable {
        val tblPr = tblNode.child("tblPr")
        val tblStyleId = tblPr?.child("tblStyle")?.attr("val")
        val tblStyle = tblStyleId?.let { styles[it] } ?: defaultStyles["table"]
        val gridCols = tblNode.child("tblGrid")?.childrenNamed("gridCol")?.mapNotNull { it.attr("w")?.toIntOrNull() } ?: emptyList()

        val baseBorders = arrayOfNulls<WBorder>(6)
        val basePad = intArrayOf(0, 108, 0, 108)
        var baseShading: WShading? = null
        var tblJc = 0
        var tblInd = 0
        var tblBidi = false
        var tblWidthDxa = 0
        var tblLook = 0x04A0

        fun applyTblPrNode(n: Node) {
            n.child("jc")?.attr("val")?.let { tblJc = when (it) { "center" -> 1; "right", "end" -> 2; else -> 0 } }
            n.child("tblInd")?.let { ind ->
                val v = ind.attr("w")?.toIntOrNull() ?: 0
                if (ind.attr("type") != "pct") tblInd = v
            }
            n.child("tblW")?.let { w ->
                val v = w.attr("w")?.toIntOrNull() ?: 0
                if (w.attr("type") == "dxa" || w.attr("type") == null) tblWidthDxa = v
            }
            n.child("tblBorders")?.let { applyBorders6(baseBorders, it) }
            n.child("tblCellMar")?.let { applyMargins4(basePad, it) }
            n.child("shd")?.let { parseShading(it)?.let { s -> baseShading = s } }
            n.child("bidiVisual")?.let { tblBidi = boolVal(it) }
            n.child("tblLook")?.let { lk ->
                lk.attr("val")?.toIntOrNull(16)?.let { tblLook = it }
                lk.attr("firstRow")?.let { tblLook = setBit(tblLook, 0x0020, boolStr(it)) }
                lk.attr("lastRow")?.let { tblLook = setBit(tblLook, 0x0040, boolStr(it)) }
                lk.attr("firstColumn")?.let { tblLook = setBit(tblLook, 0x0080, boolStr(it)) }
                lk.attr("lastColumn")?.let { tblLook = setBit(tblLook, 0x0100, boolStr(it)) }
                lk.attr("noHBand")?.let { tblLook = setBit(tblLook, 0x0200, boolStr(it)) }
                lk.attr("noVBand")?.let { tblLook = setBit(tblLook, 0x0400, boolStr(it)) }
            }
        }

        val tblStyleChain = ArrayList<StyleDef>()
        var curS = tblStyle
        var guard = 0
        while (curS != null && guard++ < 16) {
            tblStyleChain.add(curS)
            curS = curS.basedOn?.let { styles[it] }
        }
        tblStyleChain.reverse()
        for (s in tblStyleChain) s.tblPr?.let { applyTblPrNode(it) }
        tblPr?.let { applyTblPrNode(it) }

        val condNodes = HashMap<String, Node>()
        for (s in tblStyleChain) condNodes.putAll(s.tblStylePr)

        val trNodes = ArrayList<Node>()
        fun collectRows(n: Node) {
            for (ch in n.children) {
                when (ch.name) {
                    "tr" -> trNodes.add(ch)
                    "sdt" -> ch.child("sdtContent")?.let { collectRows(it) }
                    "customXml", "ins" -> collectRows(ch)
                }
            }
        }
        collectRows(tblNode)

        val rowCount = trNodes.size
        val rows = ArrayList<WRow>(rowCount)
        val totalGridW = gridCols.sum()
        val refW = when {
            totalGridW > 0 -> totalGridW
            tblWidthDxa > 0 -> tblWidthDxa
            else -> 9360
        }

        for ((ri, tr) in trNodes.withIndex()) {
            val trPr = tr.child("trPr")
            val tp = TableProps()
            tp.jc = tblJc
            tp.dxaLeft = tblInd
            tp.tableIndent = tblInd
            tp.hasIndent = tblInd != 0
            tp.bidi = tblBidi
            tp.tableShading = baseShading
            for (k in 0..5) tp.tableBorders[k] = baseBorders[k]
            for (k in 0..3) tp.defaultPadding[k] = basePad[k]
            trPr?.child("tblHeader")?.let { tp.header = boolVal(it) }
            trPr?.child("cantSplit")?.let { tp.cantSplit = boolVal(it) }
            trPr?.child("trHeight")?.let { th ->
                val v = th.attr("val")?.toIntOrNull() ?: 0
                if (v > 0) tp.rowHeight = if (th.attr("hRule") == "exact") -v else v
            }
            val gridBefore = trPr?.child("gridBefore")?.attr("val")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

            val tcNodes = ArrayList<Node>()
            fun collectCells(n: Node) {
                for (ch in n.children) {
                    when (ch.name) {
                        "tc" -> tcNodes.add(ch)
                        "sdt" -> ch.child("sdtContent")?.let { collectCells(it) }
                        "customXml", "ins" -> collectCells(ch)
                    }
                }
            }
            collectCells(tr)
            if (tcNodes.isEmpty()) continue

            val cells = ArrayList<WCell>(tcNodes.size)
            val centers = ArrayList<Int>(tcNodes.size + 1)
            var xPos = tblInd
            for (g in 0 until gridBefore) xPos += gridCols.getOrElse(g) { 720 }
            centers.add(xPos)

            tp.ensure(tcNodes.size)
            var gridColIdx = gridBefore
            for ((ci, tc) in tcNodes.withIndex()) {
                val tcPr = tc.child("tcPr")
                val span = tcPr?.child("gridSpan")?.attr("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                var cellW = 0
                if (gridCols.isNotEmpty() && gridColIdx < gridCols.size) {
                    for (k in 0 until span) cellW += gridCols.getOrElse(gridColIdx + k) { gridCols.last() }
                }
                if (cellW <= 0) {
                    tcPr?.child("tcW")?.let { cw ->
                        val v = cw.attr("w")?.toIntOrNull() ?: 0
                        cellW = when (cw.attr("type")) {
                            "pct" -> (refW.toLong() * v / 5000L).toInt()
                            "dxa", null -> v
                            else -> 0
                        }
                    }
                }
                if (cellW <= 0) cellW = refW / maxOf(1, tcNodes.size)
                xPos += cellW
                centers.add(xPos)
                tp.cellWidths[ci] = cellW

                var flags = 0
                tcPr?.child("vMerge")?.let { vm ->
                    flags = flags or if (vm.attr("val") == "restart") 0x40 else 0x20
                }
                val vAlign = when (tcPr?.child("vAlign")?.attr("val")) { "center" -> 1; "bottom" -> 2; else -> 0 }
                flags = flags or (vAlign shl 7)
                tp.cellFlags[ci] = flags

                val isFirstRow = ri == 0 && (tblLook and 0x0020) != 0
                val isLastRow = ri == rowCount - 1 && (tblLook and 0x0040) != 0
                val isFirstCol = ci == 0 && (tblLook and 0x0080) != 0
                val isLastCol = ci == tcNodes.size - 1 && (tblLook and 0x0100) != 0
                val hBand = (tblLook and 0x0200) == 0 && !isFirstRow && !isLastRow
                val vBand = (tblLook and 0x0400) == 0 && !isFirstCol && !isLastCol
                val bandRowIdx = if ((tblLook and 0x0020) != 0) ri - 1 else ri
                val bandColIdx = if ((tblLook and 0x0080) != 0) ci - 1 else ci

                val activeConds = ArrayList<Node>()
                if (hBand) condNodes[if (bandRowIdx % 2 == 0) "band1Horz" else "band2Horz"]?.let { activeConds.add(it) }
                if (vBand) condNodes[if (bandColIdx % 2 == 0) "band1Vert" else "band2Vert"]?.let { activeConds.add(it) }
                if (isFirstCol) condNodes["firstCol"]?.let { activeConds.add(it) }
                if (isLastCol) condNodes["lastCol"]?.let { activeConds.add(it) }
                if (isFirstRow) condNodes["firstRow"]?.let { activeConds.add(it) }
                if (isLastRow) condNodes["lastRow"]?.let { activeConds.add(it) }

                for (cn in activeConds) {
                    cn.child("tcPr")?.let { ctp ->
                        ctp.child("shd")?.let { parseShading(it)?.let { sh -> tp.cellShading[ci] = sh } }
                        ctp.child("tcBorders")?.let { applyBorders4(tp.cellBorders[ci], it) }
                        ctp.child("tcMar")?.let { applyMargins4(tp.cellPadding[ci], it) }
                    }
                }
                tcPr?.child("shd")?.let { parseShading(it)?.let { sh -> tp.cellShading[ci] = sh } }
                tcPr?.child("tcBorders")?.let { applyBorders4(tp.cellBorders[ci], it) }
                tcPr?.child("tcMar")?.let { applyMargins4(tp.cellPadding[ci], it) }

                val blocks = parseContainerBlocks(tc, tableDepth, null)
                if (activeConds.isNotEmpty()) {
                    for (cn in activeConds) {
                        val crPr = cn.child("rPr") ?: continue
                        applyConditionalRunProps(blocks, crPr)
                    }
                }
                cells.add(WCell(blocks))
                gridColIdx += span
            }
            tp.centers = centers.toIntArray()
            rows.add(WRow(cells, tp))
        }
        return WTable(rows)
    }

    private fun applyConditionalRunProps(blocks: List<WBlock>, crPr: Node) {
        val bold = crPr.child("b")?.let { boolVal(it) }
        val italic = crPr.child("i")?.let { boolVal(it) }
        val col = crPr.child("color")?.let { parseColorAttr(it) }
        if (bold == null && italic == null && col == null) return
        for (b in blocks) {
            if (b is WParagraph) {
                for (inl in b.inlines) {
                    if (inl is WInline.Text) {
                        if (bold == true && !inl.props.bold) inl.props.bold = true
                        if (italic == true && !inl.props.italic) inl.props.italic = true
                        if (col != null && col != WShading.AUTO && inl.props.color == WShading.AUTO) inl.props.color = col
                    }
                }
            }
        }
    }

    private fun applyBorders6(dst: Array<WBorder?>, node: Node) {
        node.child("top")?.let { dst[0] = parseBorder(it) }
        node.child("left")?.let { dst[1] = parseBorder(it) }
        node.child("start")?.let { dst[1] = parseBorder(it) }
        node.child("bottom")?.let { dst[2] = parseBorder(it) }
        node.child("right")?.let { dst[3] = parseBorder(it) }
        node.child("end")?.let { dst[3] = parseBorder(it) }
        node.child("insideH")?.let { dst[4] = parseBorder(it) }
        node.child("insideV")?.let { dst[5] = parseBorder(it) }
    }

    private fun applyBorders4(dst: Array<WBorder?>, node: Node) {
        node.child("top")?.let { dst[0] = parseBorder(it) }
        node.child("left")?.let { dst[1] = parseBorder(it) }
        node.child("start")?.let { dst[1] = parseBorder(it) }
        node.child("bottom")?.let { dst[2] = parseBorder(it) }
        node.child("right")?.let { dst[3] = parseBorder(it) }
        node.child("end")?.let { dst[3] = parseBorder(it) }
    }

    private fun applyMargins4(dst: IntArray, node: Node) {
        node.child("top")?.attr("w")?.toIntOrNull()?.let { dst[0] = it }
        (node.child("left") ?: node.child("start"))?.attr("w")?.toIntOrNull()?.let { dst[1] = it }
        node.child("bottom")?.attr("w")?.toIntOrNull()?.let { dst[2] = it }
        (node.child("right") ?: node.child("end"))?.attr("w")?.toIntOrNull()?.let { dst[3] = it }
    }

    private fun parseSectionProps(sect: Node?): SectionProps {
        val sp = SectionProps()
        if (sect == null) return sp
        sect.child("pgSz")?.let { pg ->
            pg.attr("w")?.toIntOrNull()?.takeIf { it > 0 }?.let { sp.pageWidth = it }
            pg.attr("h")?.toIntOrNull()?.takeIf { it > 0 }?.let { sp.pageHeight = it }
            sp.landscape = pg.attr("orient") == "landscape"
        }
        sect.child("pgMar")?.let { pm ->
            pm.attr("top")?.toIntOrNull()?.let { sp.marginTop = it }
            pm.attr("bottom")?.toIntOrNull()?.let { sp.marginBottom = it }
            pm.attr("left")?.toIntOrNull()?.let { sp.marginLeft = it }
            pm.attr("right")?.toIntOrNull()?.let { sp.marginRight = it }
            pm.attr("header")?.toIntOrNull()?.let { sp.headerTop = it }
            pm.attr("footer")?.toIntOrNull()?.let { sp.footerBottom = it }
            pm.attr("gutter")?.toIntOrNull()?.let { sp.gutter = it }
        }
        sect.child("cols")?.let { cols ->
            cols.attr("num")?.toIntOrNull()?.takeIf { it > 0 }?.let { sp.columns = it }
            cols.attr("space")?.toIntOrNull()?.let { sp.columnSpacing = it }
            cols.attr("equalWidth")?.let { sp.evenlySpaced = boolStr(it) }
            cols.attr("sep")?.let { sp.lineBetween = boolStr(it) }
            val colList = cols.childrenNamed("col")
            if (colList.isNotEmpty() && !sp.evenlySpaced) {
                sp.columns = colList.size
                sp.columnWidths = IntArray(colList.size) { colList[it].attr("w")?.toIntOrNull() ?: 2000 }
                sp.columnSpacings = IntArray(colList.size) { colList[it].attr("space")?.toIntOrNull() ?: sp.columnSpacing }
            }
        }
        sect.child("type")?.attr("val")?.let {
            sp.breakCode = when (it) { "continuous" -> 0; "nextColumn" -> 1; "evenPage" -> 3; "oddPage" -> 4; else -> 2 }
        }
        sect.child("titlePg")?.let { sp.titlePage = boolVal(it) }
        sect.child("pgNumType")?.let { pn ->
            pn.attr("start")?.toIntOrNull()?.let { sp.pageNumberStart = it; sp.restartPageNumbers = true }
            sp.pageNumberFormat = when (pn.attr("fmt")) {
                "upperRoman" -> 1; "lowerRoman" -> 2; "upperLetter" -> 3; "lowerLetter" -> 4; else -> 0
            }
        }
        sect.child("pgBorders")?.let { pb ->
            pb.child("top")?.let { sp.pageBorders[0] = parseBorder(it) }
            pb.child("left")?.let { sp.pageBorders[1] = parseBorder(it) }
            pb.child("bottom")?.let { sp.pageBorders[2] = parseBorder(it) }
            pb.child("right")?.let { sp.pageBorders[3] = parseBorder(it) }
        }
        return sp
    }

    private fun parseHeaderFooterPart(relId: String?): List<WBlock>? {
        if (relId == null) return null
        val path = mainRels[relId] ?: return null
        val root = parseXml(entry(path)) ?: return null
        val savedRels = activeRels
        activeRels = readRels(path)
        val savedPlain = plain.length
        val blocks = parseContainerBlocks(root, 0, null)
        plain.setLength(savedPlain)
        activeRels = savedRels
        return blocks.takeIf { it.isNotEmpty() }
    }

    private fun parseHeaderSet(sect: Node?, prev: WHeaderSet?): WHeaderSet {
        if (sect == null) return prev ?: WHeaderSet(null, null, null, null, null, null)
        var evenH: List<WBlock>? = null
        var oddH: List<WBlock>? = null
        var firstH: List<WBlock>? = null
        var evenF: List<WBlock>? = null
        var oddF: List<WBlock>? = null
        var firstF: List<WBlock>? = null
        for (h in sect.childrenNamed("headerReference")) {
            val rel = h.attr("r:id") ?: h.attr("id")
            when (h.attr("type")) {
                "even" -> evenH = parseHeaderFooterPart(rel)
                "first" -> firstH = parseHeaderFooterPart(rel)
                else -> oddH = parseHeaderFooterPart(rel)
            }
        }
        for (f in sect.childrenNamed("footerReference")) {
            val rel = f.attr("r:id") ?: f.attr("id")
            when (f.attr("type")) {
                "even" -> evenF = parseHeaderFooterPart(rel)
                "first" -> firstF = parseHeaderFooterPart(rel)
                else -> oddF = parseHeaderFooterPart(rel)
            }
        }
        return WHeaderSet(
            evenH ?: prev?.evenHeader,
            oddH ?: prev?.oddHeader,
            evenF ?: prev?.evenFooter,
            oddF ?: prev?.oddFooter,
            firstH ?: prev?.firstHeader,
            firstF ?: prev?.firstFooter
        )
    }

    private fun extractOmmlText(node: Node): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            when (n.name) {
                "t" -> sb.append(n.text)
                "f" -> {
                    sb.append('(')
                    n.child("num")?.let { walk(it) }
                    sb.append('/')
                    n.child("den")?.let { walk(it) }
                    sb.append(')')
                }
                "rad" -> {
                    sb.append('√')
                    sb.append('(')
                    n.child("e")?.let { walk(it) }
                    sb.append(')')
                }
                "sSup" -> {
                    n.child("e")?.let { walk(it) }
                    sb.append('^')
                    n.child("sup")?.let { walk(it) }
                }
                "sSub" -> {
                    n.child("e")?.let { walk(it) }
                    sb.append('_')
                    n.child("sub")?.let { walk(it) }
                }
                "d" -> {
                    val beg = n.child("dPr")?.child("begChr")?.attr("val") ?: "("
                    val end = n.child("dPr")?.child("endChr")?.attr("val") ?: ")"
                    val sep = n.child("dPr")?.child("sepChr")?.attr("val") ?: ","
                    sb.append(beg)
                    val elems = n.childrenNamed("e")
                    for ((idx, e) in elems.withIndex()) {
                        if (idx > 0) sb.append(sep)
                        walk(e)
                    }
                    sb.append(end)
                }
                "nary" -> {
                    sb.append(n.child("naryPr")?.child("chr")?.attr("val") ?: "∫")
                    n.child("sub")?.let { val t = extractOmmlText(it); if (t.isNotEmpty()) sb.append('_').append(t) }
                    n.child("sup")?.let { val t = extractOmmlText(it); if (t.isNotEmpty()) sb.append('^').append(t) }
                    n.child("e")?.let { walk(it) }
                }
                else -> for (ch in n.children) walk(ch)
            }
        }
        walk(node)
        return sb.toString()
    }

    fun read(): WDocument? {
        val pkgRels = readRelsByType("")
        mainDocPath = pkgRels["officeDocument"] ?: if (entry("word/document.xml") != null) "word/document.xml" else "word/document2.xml"
        val docRoot = parseXml(entry(mainDocPath)) ?: return null
        val body = docRoot.child("body") ?: return null
        mainRels = readRels(mainDocPath)
        activeRels = mainRels
        val relsByType = readRelsByType(mainDocPath)

        readTheme(relsByType["theme"])
        internFont(theme.minorLatin)
        readSettings(relsByType["settings"])
        readStyles(relsByType["styles"])
        readNumbering(relsByType["numbering"])
        readNotes(relsByType["footnotes"], false)
        readNotes(relsByType["endnotes"], true)
        readComments(relsByType["comments"])

        val built = ArrayList<SectionBuild>()
        parseContainerBlocks(body, 0, built)
        if (built.isEmpty()) built.add(SectionBuild(ArrayList(), body.child("sectPr")))

        val sections = ArrayList<WSection>(built.size)
        var prevHeaders: WHeaderSet? = null
        for (sb in built) {
            val sp = parseSectionProps(sb.sectNode)
            val hs = parseHeaderSet(sb.sectNode, prevHeaders)
            prevHeaders = hs
            sections.add(WSection(sp, sb.blocks, hs))
        }
        return WDocument(
            sections = sections,
            fonts = fonts,
            footnotes = footnotes,
            endnotes = endnotes,
            facingPages = facingPages,
            defaultTab = defaultTab,
            plainText = plain.toString(),
            collapseSpacing = htmlSpacing,
            normalChar = styleChp(defaultStyles["paragraph"]?.id),
            normalPara = stylePap(defaultStyles["paragraph"]?.id),
            autoHyphen = autoHyphen,
            autoHyphenCaps = autoHyphenCaps,
            hotZoneTw = hotZoneTw,
            consecHyphLim = consecHyphLim,
            lid = docLid,
            comments = comments
        )
    }

    private fun findFirst(node: Node, name: String): Node? {
        if (node.name == name) return node
        for (c in node.children) findFirst(c, name)?.let { return it }
        return null
    }

    private fun boolVal(n: Node): Boolean {
        val v = n.attr("val") ?: return true
        return boolStr(v)
    }

    private fun boolStr(v: String): Boolean = when (v.lowercase(Locale.ROOT)) {
        "0", "false", "off", "none" -> false
        else -> true
    }

    private fun setBit(mask: Int, bit: Int, on: Boolean): Int = if (on) mask or bit else mask and bit.inv()

    private fun emuToTwips(emu: Long): Int = ((emu + 317L) / 635L).toInt()

    private fun normalizePath(p: String): String {
        val parts = ArrayList<String>()
        for (seg in p.replace('\\', '/').split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun langToLid(lang: String): Int {
        val low = lang.lowercase(Locale.ROOT)
        return when {
            low == "en-gb" -> 0x0809
            low.startsWith("en") -> 0x0409
            low.startsWith("ru") -> 0x0419
            low.startsWith("uk") -> 0x0422
            low.startsWith("be") -> 0x0423
            low.startsWith("bg") -> 0x0402
            low.startsWith("de") -> 0x0407
            low.startsWith("fr") -> 0x040C
            low.startsWith("es") -> 0x040A
            low.startsWith("it") -> 0x0410
            low.startsWith("pl") -> 0x0415
            low.startsWith("cs") -> 0x0405
            low.startsWith("sk") -> 0x041B
            low.startsWith("nl") -> 0x0413
            low.startsWith("pt") -> 0x0416
            low.startsWith("sv") -> 0x041D
            low.startsWith("nb") || low.startsWith("no") -> 0x0414
            low.startsWith("da") -> 0x0406
            low.startsWith("fi") -> 0x040B
            low.startsWith("hu") -> 0x040E
            low.startsWith("tr") -> 0x041F
            else -> 0
        }
    }

    private fun roman(n0: Int, upper: Boolean): String {
        if (n0 <= 0 || n0 >= 4000) return n0.toString()
        var n = n0
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        val sb = StringBuilder()
        for (i in vals.indices) while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i] }
        return if (upper) sb.toString().uppercase(Locale.ROOT) else sb.toString()
    }

    private fun alpha(n0: Int, upper: Boolean): String {
        if (n0 <= 0) return n0.toString()
        val base = if (upper) 'A' else 'a'
        val c = base + (n0 - 1) % 26
        return c.toString().repeat((n0 - 1) / 26 + 1)
    }

    private fun russianAlpha(n0: Int, upper: Boolean): String {
        if (n0 <= 0) return n0.toString()
        val letters = if (upper) "АБВГДЕЖЗИКЛМНОПРСТУФХЦЧШЩЫЭЮЯ" else "абвгдежзиклмнопрстуфхцчшщыэюя"
        val idx = (n0 - 1) % letters.length
        return letters[idx].toString().repeat((n0 - 1) / letters.length + 1)
    }

    private fun mapSymbolText(s: String, font: String?): String {
        if (s.isEmpty()) return s
        val f = font?.lowercase(Locale.ROOT) ?: ""
        val need = f == "symbol" || f.startsWith("wingdings") || f == "monotype sorts" || f.startsWith("zapfdingbats") || f.startsWith("zapf dingbats") || s.any { it.code in 0xF020..0xF0FF }
        if (!need) return s
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(mapSymbol(ch.code, font))
        return sb.toString()
    }

    private fun mapSymbol(code: Int, font: String?): Char {
        val f = font?.lowercase(Locale.ROOT) ?: ""
        val low = if (code in 0xF020..0xF0FF) code - 0xF000 else code
        if (f == "symbol") {
            SYMBOL_MAP[low]?.let { return it }
            return if (code in 0xF020..0xF0FF) low.toChar() else code.toChar()
        }
        if (f.startsWith("wingdings")) {
            WINGDINGS_MAP[low]?.let { return it }
            return if (code in 0xF020..0xF0FF) '▪' else code.toChar()
        }
        if (f == "monotype sorts" || f.startsWith("zapfdingbats") || f.startsWith("zapf dingbats")) {
            DINGBATS_MAP[low]?.let { return it }
            return if (code in 0xF020..0xF0FF) '▪' else code.toChar()
        }
        if (code in 0xF020..0xF0FF) {
            if (low == 0xB7 || low == 0x6C) return '•'
            if (low == 0xA7) return '▪'
            if (low == 0x6F) return '○'
            WINGDINGS_MAP[low]?.let { return it }
            return low.toChar()
        }
        return code.toChar()
    }

    companion object {
        fun unzipEntries(zipBytes: ByteArray, maxTotalBytes: Long = 96L * 1024L * 1024L, maxEntryBytes: Long = 32L * 1024L * 1024L): Map<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            var total = 0L
            val buf = ByteArray(8192)
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                while (true) {
                    val ze = try {
                        zis.nextEntry
                    } catch (zex: java.util.zip.ZipException) {
                        if (zex.message?.contains("encrypted", ignoreCase = true) == true) {
                            throw SecurityException("UNSUPPORTED_ENCRYPTION: ZIP-container password encryption is not supported.")
                        }
                        break
                    } catch (_: Throwable) { break } ?: break
                    if (ze.isDirectory) continue
                    val name = ze.name.trimStart('/')
                    if (name.isEmpty()) continue
                    val baos = ByteArrayOutputStream()
                    var entryLen = 0L
                    var entryOk = true
                    try {
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            entryLen += n
                            total += n
                            if (entryLen > maxEntryBytes || total > maxTotalBytes) { entryOk = false; break }
                            baos.write(buf, 0, n)
                        }
                    } catch (_: Throwable) {
                        entryOk = false
                    }
                    if (entryOk) {
                        out[name] = baos.toByteArray()
                    }
                    try { zis.closeEntry() } catch (_: Throwable) {}
                    if (total > maxTotalBytes) break
                }
            }
            return out
        }

        private val SYMBOL_MAP: Map<Int, Char> = run {
            val m = HashMap<Int, Char>()
            val greekUpper = "ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ"
            for ((i, c) in greekUpper.withIndex()) m[0x41 + i] = c
            val greekLower = "αβχδεφγηιϕκλμνοπθρστυϖωξψζ"
            for ((i, c) in greekLower.withIndex()) m[0x61 + i] = c
            m[0x22] = '∀'; m[0x24] = '∃'; m[0x27] = '∋'; m[0x2A] = '∗'; m[0x2D] = '−'; m[0x40] = '≅'; m[0x5C] = '∴'; m[0x5E] = '⊥'; m[0x60] = '‾'
            m[0x7E] = '∼'; m[0xA0] = '€'; m[0xA1] = 'ϒ'; m[0xA2] = '′'; m[0xA3] = '≤'; m[0xA4] = '⁄'; m[0xA5] = '∞'; m[0xA6] = 'ƒ'; m[0xA7] = '♣'
            m[0xA8] = '♦'; m[0xA9] = '♥'; m[0xAA] = '♠'; m[0xAB] = '↔'; m[0xAC] = '←'; m[0xAD] = '↑'; m[0xAE] = '→'; m[0xAF] = '↓'; m[0xB0] = '°'
            m[0xB1] = '±'; m[0xB2] = '″'; m[0xB3] = '≥'; m[0xB4] = '×'; m[0xB5] = '∝'; m[0xB6] = '∂'; m[0xB7] = '•'; m[0xB8] = '÷'; m[0xB9] = '≠'
            m[0xBA] = '≡'; m[0xBB] = '≈'; m[0xBC] = '…'; m[0xC0] = 'ℵ'; m[0xC1] = 'ℑ'; m[0xC2] = 'ℜ'; m[0xC3] = '℘'; m[0xC4] = '⊗'; m[0xC5] = '⊕'
            m[0xC6] = '∅'; m[0xC7] = '∩'; m[0xC8] = '∪'; m[0xC9] = '⊃'; m[0xCA] = '⊇'; m[0xCB] = '⊄'; m[0xCC] = '⊂'; m[0xCD] = '⊆'; m[0xCE] = '∈'
            m[0xCF] = '∉'; m[0xD0] = '∠'; m[0xD1] = '∇'; m[0xD2] = '®'; m[0xD3] = '©'; m[0xD4] = '™'; m[0xD5] = '∏'; m[0xD6] = '√'; m[0xD7] = '⋅'
            m[0xD8] = '¬'; m[0xD9] = '∧'; m[0xDA] = '∨'; m[0xDB] = '⇔'; m[0xDC] = '⇐'; m[0xDD] = '⇑'; m[0xDE] = '⇒'; m[0xDF] = '⇓'; m[0xE0] = '◊'
            m[0xE1] = '〈'; m[0xE5] = '∑'; m[0xF1] = '〉'; m[0xF2] = '∫'
            m
        }
        private val WINGDINGS_MAP: Map<Int, Char> = mapOf(
            0x6C to '●', 0x6E to '■', 0x6F to '□', 0x71 to '❑', 0x75 to '◆', 0x76 to '❖', 0x77 to '◆', 0xA7 to '▪', 0xA8 to '◻',
            0xD8 to '➢', 0xE0 to '→', 0xE8 to '➔', 0xF0 to '⇨', 0xFC to '✓', 0xFB to '✗', 0xFE to '☑', 0x9F to '•', 0xA1 to '○', 0x4A to '☺',
            0x46 to '☞', 0x3F to '✍', 0x21 to '✏', 0x22 to '✂', 0x28 to '☎', 0x2A to '✉', 0x7B to '❀', 0x7C to '✿'
        )
        private val DINGBATS_MAP: Map<Int, Char> = mapOf(
            0x33 to '✓', 0x34 to '✔', 0x35 to '✕', 0x36 to '✖', 0x37 to '✗', 0x38 to '✘', 0x46 to '★', 0x48 to '★', 0x49 to '✩',
            0x6C to '●', 0x6D to '❍', 0x6E to '■', 0x6F to '❏', 0x70 to '❐', 0x71 to '❑', 0x72 to '❒', 0x73 to '▲', 0x74 to '▼',
            0x75 to '◆', 0x76 to '❖', 0x77 to '◗', 0xD4 to '→', 0xDC to '➔', 0xE4 to '➢', 0xE5 to '➣', 0xE6 to '➤'
        )
    }
}
