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
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class DmlNode(val name: String, val attrs: Map<String, String>, val children: MutableList<DmlNode> = ArrayList()) {
    var text: String = ""

    fun attr(key: String): String? = attrs[key]
    fun int(key: String): Int? = attrs[key]?.trim()?.toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
    fun long(key: String): Long? = attrs[key]?.trim()?.toLongOrNull()
    fun bool(key: String): Boolean? = attrs[key]?.let { it == "1" || it.equals("true", true) || it.equals("on", true) }

    fun child(name: String): DmlNode? = children.firstOrNull { it.name == name }
    fun all(name: String): List<DmlNode> = children.filter { it.name == name }
    fun path(vararg names: String): DmlNode? {
        var n: DmlNode? = this
        for (s in names) n = n?.child(s) ?: return null
        return n
    }

    fun descendants(name: String, out: MutableList<DmlNode> = ArrayList(), depth: Int = 0): List<DmlNode> {
        if (depth > 64) return out
        for (c in children) {
            if (c.name == name) out.add(c)
            c.descendants(name, out, depth + 1)
        }
        return out
    }

    fun allText(): String {
        val sb = StringBuilder()
        fun walk(n: DmlNode, d: Int) {
            if (d > 64) return
            if (n.name == "t") sb.append(n.text)
            for (c in n.children) walk(c, d + 1)
        }
        walk(this, 0)
        return sb.toString()
    }

    companion object {
        const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val MAX_NODES = 2_000_000
        private val TEXT_NODES = setOf("t", "v", "f", "formatCode", "tableStyleId", "col", "colOff", "row", "rowOff")

        fun parse(bytes: ByteArray?): DmlNode? {
            if (bytes == null || bytes.isEmpty()) return null
            return try {
                val parser = EbookTextSession.createSafeXmlParser()
                parser.setInput(ByteArrayInputStream(bytes), null)
                val stack = ArrayList<DmlNode>()
                var root: DmlNode? = null
                var count = 0
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        XmlPullParser.START_TAG -> {
                            if (++count > MAX_NODES || stack.size > 256) return root
                            val attrs = HashMap<String, String>(parser.attributeCount * 2)
                            for (i in 0 until parser.attributeCount) {
                                val ns = parser.getAttributeNamespace(i)
                                val local = parser.getAttributeName(i).substringAfterLast(':')
                                val key = if (ns == NS_REL) "r:$local" else local
                                attrs[key] = parser.getAttributeValue(i)
                            }
                            val node = DmlNode(parser.name.substringAfterLast(':'), attrs)
                            if (stack.isEmpty()) root = node else stack.last().children.add(node)
                            stack.add(node)
                        }
                        XmlPullParser.TEXT -> if (stack.isNotEmpty()) {
                            val top = stack.last()
                            if (top.name in TEXT_NODES) {
                                top.text += parser.text
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (stack.isNotEmpty()) {
                                val done = stack.removeAt(stack.size - 1)
                                if (done.name == "AlternateContent" && stack.isNotEmpty()) {
                                    val parent = stack.last()
                                    val idx = parent.children.lastIndexOf(done)
                                    val pick = done.child("Fallback") ?: done.child("Choice")
                                    if (idx >= 0) {
                                        parent.children.removeAt(idx)
                                        if (pick != null) parent.children.addAll(idx, pick.children)
                                    }
                                }
                            }
                        }
                    }
                    ev = parser.next()
                }
                root
            } catch (_: Throwable) {
                null
            }
        }
    }
}

class DmlPackage(private val parts: Map<String, ByteArray>) {
    private val xmlCache = HashMap<String, DmlNode?>()
    private val relCache = HashMap<String, Map<String, DmlRel>>()

    class DmlRel(val id: String, val type: String, val target: String, val external: Boolean)

    fun bytes(path: String): ByteArray? = parts[normalize(path)] ?: parts.entries.firstOrNull { it.key.equals(normalize(path), true) }?.value

    fun xml(path: String): DmlNode? = synchronized(xmlCache) { xmlCache.getOrPut(normalize(path)) { DmlNode.parse(bytes(path)) } }

    fun rels(partPath: String): Map<String, DmlRel> = synchronized(relCache) {
        relCache.getOrPut(normalize(partPath)) {
            val p = normalize(partPath)
            val dir = p.substringBeforeLast('/', "")
            val name = p.substringAfterLast('/')
            val relPath = if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
            val root = DmlNode.parse(bytes(relPath)) ?: return@getOrPut emptyMap()
            val out = HashMap<String, DmlRel>()
            for (r in root.all("Relationship")) {
                val id = r.attr("Id") ?: continue
                val external = r.attr("TargetMode").equals("External", true)
                val target = r.attr("Target") ?: continue
                out[id] = DmlRel(id, r.attr("Type") ?: "", if (external) target else resolve(dir, target), external)
            }
            out
        }
    }

    fun relTarget(partPath: String, id: String?): String? = id?.let { rels(partPath)[it] }?.takeIf { !it.external }?.target

    fun relByType(partPath: String, suffix: String): String? = rels(partPath).values.firstOrNull { !it.external && it.type.endsWith(suffix) }?.target

    companion object {
        fun normalize(p: String): String = p.replace('\\', '/').trimStart('/')

        fun resolve(dir: String, target: String): String {
            if (target.startsWith("/")) return normalize(target)
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
            for (seg in target.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/")
        }
    }
}

class DmlTheme(root: DmlNode?) {
    val colors = HashMap<String, Int>()
    var majorLatin: String = "Calibri"
    var minorLatin: String = "Calibri"
    var majorEa: String? = null
    var minorEa: String? = null
    val fillStyles: List<DmlNode>
    val lineStyles: List<DmlNode>
    val effectStyles: List<DmlNode>
    val bgFillStyles: List<DmlNode>

    init {
        colors["dk1"] = Color.BLACK
        colors["lt1"] = Color.WHITE
        colors["dk2"] = Color.rgb(0x44, 0x54, 0x6A)
        colors["lt2"] = Color.rgb(0xE7, 0xE6, 0xE6)
        colors["accent1"] = Color.rgb(0x44, 0x72, 0xC4)
        colors["accent2"] = Color.rgb(0xED, 0x7D, 0x31)
        colors["accent3"] = Color.rgb(0xA5, 0xA5, 0xA5)
        colors["accent4"] = Color.rgb(0xFF, 0xC0, 0x00)
        colors["accent5"] = Color.rgb(0x5B, 0x9B, 0xD5)
        colors["accent6"] = Color.rgb(0x70, 0xAD, 0x47)
        colors["hlink"] = Color.rgb(0x05, 0x63, 0xC1)
        colors["folHlink"] = Color.rgb(0x95, 0x4F, 0x72)
        val te = root?.child("themeElements")
        te?.child("clrScheme")?.children?.forEach { slot ->
            val c = slot.children.firstOrNull() ?: return@forEach
            val v = when (c.name) {
                "srgbClr" -> c.attr("val")?.let { parseHex(it) }
                "sysClr" -> (c.attr("lastClr") ?: c.attr("val"))?.let { parseHex(it) ?: sysColor(it) }
                else -> null
            }
            if (v != null) colors[slot.name] = v
        }
        te?.child("fontScheme")?.let { fs ->
            fs.path("majorFont", "latin")?.attr("typeface")?.takeIf { it.isNotBlank() }?.let { majorLatin = it }
            fs.path("minorFont", "latin")?.attr("typeface")?.takeIf { it.isNotBlank() }?.let { minorLatin = it }
            majorEa = fs.path("majorFont", "ea")?.attr("typeface")?.takeIf { it.isNotBlank() }
            minorEa = fs.path("minorFont", "ea")?.attr("typeface")?.takeIf { it.isNotBlank() }
        }
        val fmt = te?.child("fmtScheme")
        fillStyles = fmt?.child("fillStyleLst")?.children.orEmpty()
        lineStyles = fmt?.child("lnStyleLst")?.children.orEmpty()
        effectStyles = fmt?.child("effectStyleLst")?.children.orEmpty()
        bgFillStyles = fmt?.child("bgFillStyleLst")?.children.orEmpty()
    }

    companion object {
        fun parseHex(s: String): Int? {
            val h = s.trim()
            if (h.length != 6) return null
            return h.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
        }

        fun sysColor(name: String): Int? = when (name) {
            "windowText", "btnText", "captionText", "menuText", "infoText" -> Color.BLACK
            "window", "btnHighlight", "menu", "highlightText" -> Color.WHITE
            "btnFace", "menuBar", "3dLight" -> Color.rgb(0xF0, 0xF0, 0xF0)
            "btnShadow", "grayText" -> Color.rgb(0xA0, 0xA0, 0xA0)
            "highlight" -> Color.rgb(0x33, 0x99, 0xFF)
            "infoBk" -> Color.rgb(0xFF, 0xFF, 0xE1)
            else -> null
        }
    }
}

class DmlColorContext(
    val theme: DmlTheme,
    val clrMap: Map<String, String>,
    val phClr: Int? = null
) {
    fun withPh(c: Int?): DmlColorContext = DmlColorContext(theme, clrMap, c)

    fun scheme(name: String): Int {
        if (name == "phClr") return phClr ?: Color.BLACK
        val mapped = clrMap[name] ?: when (name) {
            "bg1" -> "lt1"
            "tx1" -> "dk1"
            "bg2" -> "lt2"
            "tx2" -> "dk2"
            else -> name
        }
        return theme.colors[mapped] ?: theme.colors[name] ?: Color.BLACK
    }

    fun color(node: DmlNode?): Int? {
        if (node == null) return null
        val base: Int = when (node.name) {
            "srgbClr" -> DmlTheme.parseHex(node.attr("val") ?: "") ?: return null
            "schemeClr" -> scheme(node.attr("val") ?: return null)
            "sysClr" -> (node.attr("lastClr")?.let { DmlTheme.parseHex(it) }) ?: DmlTheme.sysColor(node.attr("val") ?: "") ?: Color.BLACK
            "prstClr" -> DmlColors.preset(node.attr("val") ?: "") ?: return null
            "hslClr" -> {
                val h = (node.int("hue") ?: 0) / 60000f
                val s = (node.int("sat") ?: 0) / 100000f
                val l = (node.int("lum") ?: 0) / 100000f
                DmlColors.hslToRgb(h, s, l)
            }
            "scrgbClr" -> {
                val r = (node.int("r") ?: 0) / 100000f
                val g = (node.int("g") ?: 0) / 100000f
                val b = (node.int("b") ?: 0) / 100000f
                Color.rgb(DmlColors.toSrgb(r), DmlColors.toSrgb(g), DmlColors.toSrgb(b))
            }
            else -> return null
        }
        return DmlColors.applyTransforms(base, node.children)
    }

    fun colorIn(parent: DmlNode?): Int? = parent?.children?.firstOrNull { it.name.endsWith("Clr") }?.let { color(it) }
}

object DmlColors {
    fun toLinear(c: Int): Float {
        val v = c / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    fun toSrgb(v: Float): Int {
        val x = v.coerceIn(0f, 1f)
        val c = if (x <= 0.0031308f) x * 12.92f else (1.055f * x.toDouble().pow(1 / 2.4).toFloat() - 0.055f)
        return (c * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    fun rgbToHsl(c: Int): FloatArray {
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val b = Color.blue(c) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return floatArrayOf(h, s, l)
    }

    fun hslToRgb(h: Float, s: Float, l: Float, alpha: Int = 255): Int {
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        if (ss == 0f) {
            val v = (ll * 255f + 0.5f).toInt()
            return Color.argb(alpha, v, v, v)
        }
        val q = if (ll < 0.5f) ll * (1 + ss) else ll + ss - ll * ss
        val p = 2 * ll - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1f
            if (t > 1) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        val hh = (((h % 360f) + 360f) % 360f) / 360f
        return Color.argb(alpha, (hue(hh + 1f / 3f) * 255f + 0.5f).toInt(), (hue(hh) * 255f + 0.5f).toInt(), (hue(hh - 1f / 3f) * 255f + 0.5f).toInt())
    }

    fun applyTransforms(base: Int, mods: List<DmlNode>): Int {
        var r = Color.red(base)
        var g = Color.green(base)
        var b = Color.blue(base)
        var a = Color.alpha(base) / 255f
        for (m in mods) {
            val v = (m.int("val") ?: 0) / 100000f
            when (m.name) {
                "alpha" -> a = v
                "alphaMod" -> a *= v
                "alphaOff" -> a += v
                "lumMod", "lumOff", "lum", "satMod", "satOff", "sat", "hueMod", "hueOff", "hue" -> {
                    val hsl = rgbToHsl(Color.rgb(r, g, b))
                    when (m.name) {
                        "lumMod" -> hsl[2] *= v
                        "lumOff" -> hsl[2] += v
                        "lum" -> hsl[2] = v
                        "satMod" -> hsl[1] *= v
                        "satOff" -> hsl[1] += v
                        "sat" -> hsl[1] = v
                        "hueMod" -> hsl[0] *= v
                        "hueOff" -> hsl[0] += (m.int("val") ?: 0) / 60000f
                        "hue" -> hsl[0] = (m.int("val") ?: 0) / 60000f
                    }
                    val c = hslToRgb(hsl[0], hsl[1], hsl[2])
                    r = Color.red(c); g = Color.green(c); b = Color.blue(c)
                }
                "shade" -> {
                    r = toSrgb(toLinear(r) * v); g = toSrgb(toLinear(g) * v); b = toSrgb(toLinear(b) * v)
                }
                "tint" -> {
                    r = toSrgb(toLinear(r) * v + (1 - v)); g = toSrgb(toLinear(g) * v + (1 - v)); b = toSrgb(toLinear(b) * v + (1 - v))
                }
                "inv" -> { r = 255 - r; g = 255 - g; b = 255 - b }
                "comp" -> {
                    val hsl = rgbToHsl(Color.rgb(r, g, b))
                    val c = hslToRgb(hsl[0] + 180f, hsl[1], hsl[2])
                    r = Color.red(c); g = Color.green(c); b = Color.blue(c)
                }
                "gray" -> {
                    val y = (r * 0.299f + g * 0.587f + b * 0.114f).toInt().coerceIn(0, 255)
                    r = y; g = y; b = y
                }
                "gamma" -> { r = toSrgb(r / 255f); g = toSrgb(g / 255f); b = toSrgb(b / 255f) }
                "invGamma" -> { r = (toLinear(r) * 255f + 0.5f).toInt(); g = (toLinear(g) * 255f + 0.5f).toInt(); b = (toLinear(b) * 255f + 0.5f).toInt() }
                "red" -> r = (v * 255f).toInt()
                "redMod" -> r = (r * v).toInt()
                "redOff" -> r = (r + v * 255f).toInt()
                "green" -> g = (v * 255f).toInt()
                "greenMod" -> g = (g * v).toInt()
                "greenOff" -> g = (g + v * 255f).toInt()
                "blue" -> b = (v * 255f).toInt()
                "blueMod" -> b = (b * v).toInt()
                "blueOff" -> b = (b + v * 255f).toInt()
            }
            r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
        }
        return Color.argb((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt(), r, g, b)
    }

    fun preset(name: String): Int? = PRESETS[name.lowercase(Locale.ROOT)]?.let { 0xFF000000.toInt() or it }

    private val PRESETS: Map<String, Int> = mapOf(
        "aliceblue" to 0xF0F8FF, "antiquewhite" to 0xFAEBD7, "aqua" to 0x00FFFF, "aquamarine" to 0x7FFFD4, "azure" to 0xF0FFFF,
        "beige" to 0xF5F5DC, "bisque" to 0xFFE4C4, "black" to 0x000000, "blanchedalmond" to 0xFFEBCD, "blue" to 0x0000FF,
        "blueviolet" to 0x8A2BE2, "brown" to 0xA52A2A, "burlywood" to 0xDEB887, "cadetblue" to 0x5F9EA0, "chartreuse" to 0x7FFF00,
        "chocolate" to 0xD2691E, "coral" to 0xFF7F50, "cornflowerblue" to 0x6495ED, "cornsilk" to 0xFFF8DC, "crimson" to 0xDC143C,
        "cyan" to 0x00FFFF, "darkblue" to 0x00008B, "dkblue" to 0x00008B, "darkcyan" to 0x008B8B, "dkcyan" to 0x008B8B,
        "darkgoldenrod" to 0xB8860B, "dkgoldenrod" to 0xB8860B, "darkgray" to 0xA9A9A9, "dkgray" to 0xA9A9A9, "darkgrey" to 0xA9A9A9,
        "dkgrey" to 0xA9A9A9, "darkgreen" to 0x006400, "dkgreen" to 0x006400, "darkkhaki" to 0xBDB76B, "dkkhaki" to 0xBDB76B,
        "darkmagenta" to 0x8B008B, "dkmagenta" to 0x8B008B, "darkolivegreen" to 0x556B2F, "dkolivegreen" to 0x556B2F,
        "darkorange" to 0xFF8C00, "dkorange" to 0xFF8C00, "darkorchid" to 0x9932CC, "dkorchid" to 0x9932CC, "darkred" to 0x8B0000,
        "dkred" to 0x8B0000, "darksalmon" to 0xE9967A, "dksalmon" to 0xE9967A, "darkseagreen" to 0x8FBC8F, "dkseagreen" to 0x8FBC8F,
        "darkslateblue" to 0x483D8B, "dkslateblue" to 0x483D8B, "darkslategray" to 0x2F4F4F, "dkslategray" to 0x2F4F4F,
        "darkslategrey" to 0x2F4F4F, "dkslategrey" to 0x2F4F4F, "darkturquoise" to 0x00CED1, "dkturquoise" to 0x00CED1,
        "darkviolet" to 0x9400D3, "dkviolet" to 0x9400D3, "deeppink" to 0xFF1493, "deepskyblue" to 0x00BFFF, "dimgray" to 0x696969,
        "dimgrey" to 0x696969, "dodgerblue" to 0x1E90FF, "firebrick" to 0xB22222, "floralwhite" to 0xFFFAF0, "forestgreen" to 0x228B22,
        "fuchsia" to 0xFF00FF, "gainsboro" to 0xDCDCDC, "ghostwhite" to 0xF8F8FF, "gold" to 0xFFD700, "goldenrod" to 0xDAA520,
        "gray" to 0x808080, "grey" to 0x808080, "green" to 0x008000, "greenyellow" to 0xADFF2F, "honeydew" to 0xF0FFF0,
        "hotpink" to 0xFF69B4, "indianred" to 0xCD5C5C, "indigo" to 0x4B0082, "ivory" to 0xFFFFF0, "khaki" to 0xF0E68C,
        "lavender" to 0xE6E6FA, "lavenderblush" to 0xFFF0F5, "lawngreen" to 0x7CFC00, "lemonchiffon" to 0xFFFACD,
        "lightblue" to 0xADD8E6, "ltblue" to 0xADD8E6, "lightcoral" to 0xF08080, "ltcoral" to 0xF08080, "lightcyan" to 0xE0FFFF,
        "ltcyan" to 0xE0FFFF, "lightgoldenrodyellow" to 0xFAFAD2, "ltgoldenrodyellow" to 0xFAFAD2, "lightgray" to 0xD3D3D3,
        "ltgray" to 0xD3D3D3, "lightgrey" to 0xD3D3D3, "ltgrey" to 0xD3D3D3, "lightgreen" to 0x90EE90, "ltgreen" to 0x90EE90,
        "lightpink" to 0xFFB6C1, "ltpink" to 0xFFB6C1, "lightsalmon" to 0xFFA07A, "ltsalmon" to 0xFFA07A,
        "lightseagreen" to 0x20B2AA, "ltseagreen" to 0x20B2AA, "lightskyblue" to 0x87CEFA, "ltskyblue" to 0x87CEFA,
        "lightslategray" to 0x778899, "ltslategray" to 0x778899, "lightslategrey" to 0x778899, "ltslategrey" to 0x778899,
        "lightsteelblue" to 0xB0C4DE, "ltsteelblue" to 0xB0C4DE, "lightyellow" to 0xFFFFE0, "ltyellow" to 0xFFFFE0,
        "lime" to 0x00FF00, "limegreen" to 0x32CD32, "linen" to 0xFAF0E6, "magenta" to 0xFF00FF, "maroon" to 0x800000,
        "medaquamarine" to 0x66CDAA, "mediumaquamarine" to 0x66CDAA, "medblue" to 0x0000CD, "mediumblue" to 0x0000CD,
        "medorchid" to 0xBA55D3, "mediumorchid" to 0xBA55D3, "medpurple" to 0x9370DB, "mediumpurple" to 0x9370DB,
        "medseagreen" to 0x3CB371, "mediumseagreen" to 0x3CB371, "medslateblue" to 0x7B68EE, "mediumslateblue" to 0x7B68EE,
        "medspringgreen" to 0x00FA9A, "mediumspringgreen" to 0x00FA9A, "medturquoise" to 0x48D1CC, "mediumturquoise" to 0x48D1CC,
        "medvioletred" to 0xC71585, "mediumvioletred" to 0xC71585, "midnightblue" to 0x191970, "mintcream" to 0xF5FFFA,
        "mistyrose" to 0xFFE4E1, "moccasin" to 0xFFE4B5, "navajowhite" to 0xFFDEAD, "navy" to 0x000080, "oldlace" to 0xFDF5E6,
        "olive" to 0x808000, "olivedrab" to 0x6B8E23, "orange" to 0xFFA500, "orangered" to 0xFF4500, "orchid" to 0xDA70D6,
        "palegoldenrod" to 0xEEE8AA, "palegreen" to 0x98FB98, "paleturquoise" to 0xAFEEEE, "palevioletred" to 0xDB7093,
        "papayawhip" to 0xFFEFD5, "peachpuff" to 0xFFDAB9, "peru" to 0xCD853F, "pink" to 0xFFC0CB, "plum" to 0xDDA0DD,
        "powderblue" to 0xB0E0E6, "purple" to 0x800080, "red" to 0xFF0000, "rosybrown" to 0xBC8F8F, "royalblue" to 0x4169E1,
        "saddlebrown" to 0x8B4513, "salmon" to 0xFA8072, "sandybrown" to 0xF4A460, "seagreen" to 0x2E8B57, "seashell" to 0xFFF5EE,
        "sienna" to 0xA0522D, "silver" to 0xC0C0C0, "skyblue" to 0x87CEEB, "slateblue" to 0x6A5ACD, "slategray" to 0x708090,
        "slategrey" to 0x708090, "snow" to 0xFFFAFA, "springgreen" to 0x00FF7F, "steelblue" to 0x4682B4, "tan" to 0xD2B48C,
        "teal" to 0x008080, "thistle" to 0xD8BFD8, "tomato" to 0xFF6347, "turquoise" to 0x40E0D0, "violet" to 0xEE82EE,
        "wheat" to 0xF5DEB3, "white" to 0xFFFFFF, "whitesmoke" to 0xF5F5F5, "yellow" to 0xFFFF00, "yellowgreen" to 0x9ACD32
    )
}
