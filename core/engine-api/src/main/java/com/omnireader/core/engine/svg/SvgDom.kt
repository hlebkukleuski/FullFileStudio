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
 * Portions ported and adapted from resvg, usvg, svgtypes, simplecss, unicode-vo,
 * fontdb, tiny-skia, and kurbo (https://github.com/linebender/resvg),
 * Copyright (c) Yevhenii Reizner, The resvg Authors, and The Linebender Authors,
 * licensed under Apache-2.0 / MIT (tiny-skia under BSD-3-Clause; fontdb under MIT).
 * Ported to Kotlin and modified for FullFileStudio in 2026.
 */

package com.omnireader.core.engine.svg

internal class SvgXmlException(msg: String) : Exception(msg)

internal sealed class XNode {
    var parent: XElem? = null
}

internal class XText(val text: String) : XNode()

internal class XAttr(val ns: String?, val local: String, val value: String)

internal class XElem(val ns: String?, val local: String, val attrs: List<XAttr>) : XNode(), SvgCssElement {
    val children = ArrayList<XNode>()

    fun attr(local: String): String? = attrs.firstOrNull { it.ns == null && it.local == local }?.value
    fun attrNs(ns: String, local: String): String? = attrs.firstOrNull { it.ns == ns && it.local == local }?.value
    fun parentElem(): XElem? = parent
    fun prevSiblingElem(): XElem? {
        val p = parent ?: return null
        val idx = p.children.indexOf(this)
        for (i in idx - 1 downTo 0) { val c = p.children[i]; if (c is XElem) return c }
        return null
    }
    fun descendants(): Sequence<XNode> = sequence {
        yield(this@XElem)
        for (c in children) if (c is XElem) yieldAll(c.descendants()) else yield(c)
    }

    override fun cssParent(): SvgCssElement? = parent
    override fun cssPrevSibling(): SvgCssElement? = prevSiblingElem()
    override fun cssLocalName(): String = local
    override fun cssAttribute(name: String): String? = attr(name)
}

internal object SvgXml {
    const val SVG_NS = "http://www.w3.org/2000/svg"
    const val XLINK_NS = "http://www.w3.org/1999/xlink"
    const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    private class P(val s: String) {
        var i = 0
        fun eof() = i >= s.length
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun starts(t: String) = s.startsWith(t, i)
        fun expect(t: String) { if (!starts(t)) throw SvgXmlException("expected $t"); i += t.length }
        fun skipWs() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }
        fun name(): String {
            val st = i
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '=' || c == '>' || c == '/' || c == '"' || c == '\'' || c == '<') break
                i++
            }
            if (st == i) throw SvgXmlException("name")
            return s.substring(st, i)
        }
    }

    private fun decodeRefs(raw: String, entities: Map<String, String>, attr: Boolean, depth: Int = 0): String {
        if (raw.indexOf('&') < 0 && !(attr && raw.any { it == '\n' || it == '\t' || it == '\r' })) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '&') {
                val semi = raw.indexOf(';', i)
                if (semi < 0) throw SvgXmlException("entity")
                val name = raw.substring(i + 1, semi)
                when {
                    name.startsWith("#x") -> sb.appendCodePoint(name.substring(2).toInt(16))
                    name.startsWith("#") -> sb.appendCodePoint(name.substring(1).toInt())
                    name == "lt" -> sb.append('<')
                    name == "gt" -> sb.append('>')
                    name == "amp" -> sb.append('&')
                    name == "quot" -> sb.append('"')
                    name == "apos" -> sb.append('\'')
                    else -> {
                        val v = entities[name] ?: throw SvgXmlException("unknown entity $name")
                        if (depth > 8) throw SvgXmlException("entity depth")
                        sb.append(decodeRefs(v, entities, attr, depth + 1))
                    }
                }
                i = semi + 1
                continue
            }
            if (attr && (c == '\n' || c == '\t' || c == '\r')) sb.append(' ') else sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun expandEntities(body: String, entities: Map<String, String>, depth: Int = 0): String {
        if (entities.isEmpty() || depth > 8) return body
        val sb = StringBuilder(body.length)
        var i = 0
        var changed = false
        while (i < body.length) {
            val c = body[i]
            if (c == '&') {
                val semi = body.indexOf(';', i)
                if (semi > i) {
                    val name = body.substring(i + 1, semi)
                    val v = entities[name]
                    if (v != null && v.contains('<')) { sb.append(v); i = semi + 1; changed = true; continue }
                }
            }
            if (c == '<' && body.startsWith("<![CDATA[", i)) {
                val end = body.indexOf("]]>", i)
                val stop = if (end < 0) body.length else end + 3
                sb.append(body, i, stop)
                i = stop
                continue
            }
            sb.append(c)
            i++
        }
        return if (changed) expandEntities(sb.toString(), entities, depth + 1) else body
    }

    fun parse(text: String): XElem {
        val p = P(text)
        val entities = HashMap<String, String>()
        if (p.starts("﻿")) p.i++
        while (true) {
            p.skipWs()
            when {
                p.starts("<?") -> { val e = text.indexOf("?>", p.i); if (e < 0) throw SvgXmlException("pi"); p.i = e + 2 }
                p.starts("<!--") -> { val e = text.indexOf("-->", p.i); if (e < 0) throw SvgXmlException("comment"); p.i = e + 3 }
                p.starts("<!DOCTYPE") -> {
                    var depth = 0
                    val start = p.i
                    while (!p.eof()) {
                        val c = p.peek()
                        if (c == '[') depth++
                        else if (c == ']') depth--
                        else if (c == '>' && depth <= 0) break
                        else if (c == '"' || c == '\'') { val e = text.indexOf(c, p.i + 1); if (e < 0) break; p.i = e }
                        p.i++
                    }
                    val doctype = text.substring(start, p.i)
                    p.i++
                    val re = Regex("<!ENTITY\\s+([^\\s%]+)\\s+(\"([^\"]*)\"|'([^']*)')\\s*>")
                    for (m in re.findAll(doctype)) entities[m.groupValues[1]] = m.groupValues[3].ifEmpty { m.groupValues[4] }
                }
                else -> break
            }
        }
        val body = if (entities.isEmpty()) text.substring(p.i) else expandEntities(text.substring(p.i), entities)
        val q = P(body)
        val root = parseElement(q, entities, ArrayDeque<Map<String, String>>().also { it.addLast(mapOf("xml" to XML_NS)) }) ?: throw SvgXmlException("no root")
        return root
    }

    private fun parseElement(p: P, entities: Map<String, String>, scopes: ArrayDeque<Map<String, String>>): XElem? {
        while (true) {
            p.skipWs()
            when {
                p.starts("<!--") -> { val e = p.s.indexOf("-->", p.i); if (e < 0) throw SvgXmlException("comment"); p.i = e + 3 }
                p.starts("<?") -> { val e = p.s.indexOf("?>", p.i); if (e < 0) throw SvgXmlException("pi"); p.i = e + 2 }
                p.starts("<") -> break
                p.eof() -> return null
                else -> throw SvgXmlException("text before root")
            }
        }
        return element(p, entities, scopes)
    }

    private fun element(p: P, entities: Map<String, String>, scopes: ArrayDeque<Map<String, String>>): XElem {
        p.expect("<")
        val qname = p.name()
        val raw = ArrayList<Pair<String, String>>()
        while (true) {
            p.skipWs()
            if (p.starts("/>") || p.starts(">")) break
            val an = p.name()
            p.skipWs()
            p.expect("=")
            p.skipWs()
            val q = p.peek()
            if (q != '"' && q != '\'') throw SvgXmlException("attr quote")
            val e = p.s.indexOf(q, p.i + 1)
            if (e < 0) throw SvgXmlException("attr end")
            val v = p.s.substring(p.i + 1, e)
            if (v.contains('<')) throw SvgXmlException("< in attr")
            raw.add(an to decodeRefs(v, entities, true))
            p.i = e + 1
        }
        val scope = HashMap<String, String>()
        for ((n, v) in raw) {
            if (n == "xmlns") scope[""] = v else if (n.startsWith("xmlns:")) scope[n.substring(6)] = v
        }
        scopes.addLast(scope)
        fun resolve(prefix: String): String? {
            for (i in scopes.indices.reversed()) scopes.elementAt(i)[prefix]?.let { return it.ifEmpty { null } }
            return null
        }
        val (eprefix, elocal) = qname.indexOf(':').let { if (it < 0) "" to qname else qname.substring(0, it) to qname.substring(it + 1) }
        val ens = if (eprefix.isEmpty()) resolve("") else resolve(eprefix) ?: throw SvgXmlException("unknown prefix $eprefix")
        val attrs = ArrayList<XAttr>()
        for ((n, v) in raw) {
            if (n == "xmlns" || n.startsWith("xmlns:")) continue
            val idx = n.indexOf(':')
            if (idx < 0) attrs.add(XAttr(null, n, v)) else {
                val pre = n.substring(0, idx)
                val ns = if (pre == "xml") XML_NS else resolve(pre) ?: throw SvgXmlException("unknown prefix $pre")
                attrs.add(XAttr(ns, n.substring(idx + 1), v))
            }
        }
        val el = XElem(ens, elocal, attrs)
        if (p.starts("/>")) { p.i += 2; scopes.removeLast(); return el }
        p.expect(">")
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { el.children.add(XText(text.toString()).also { it.parent = el }); text.setLength(0) } }
        while (true) {
            if (p.eof()) throw SvgXmlException("unclosed $qname")
            when {
                p.starts("</") -> {
                    flush()
                    p.i += 2
                    val n = p.name()
                    if (n != qname) throw SvgXmlException("mismatched $n")
                    p.skipWs()
                    p.expect(">")
                    scopes.removeLast()
                    return el
                }
                p.starts("<!--") -> { val e = p.s.indexOf("-->", p.i); if (e < 0) throw SvgXmlException("comment"); p.i = e + 3 }
                p.starts("<![CDATA[") -> { val e = p.s.indexOf("]]>", p.i); if (e < 0) throw SvgXmlException("cdata"); text.append(p.s, p.i + 9, e); p.i = e + 3 }
                p.starts("<?") -> { val e = p.s.indexOf("?>", p.i); if (e < 0) throw SvgXmlException("pi"); p.i = e + 2 }
                p.starts("<") -> { flush(); val c = element(p, entities, scopes); c.parent = el; el.children.add(c) }
                else -> {
                    val e = p.s.indexOf('<', p.i).let { if (it < 0) p.s.length else it }
                    text.append(decodeRefs(p.s.substring(p.i, e).replace("\r\n", "\n").replace('\r', '\n'), entities, false))
                    p.i = e
                }
            }
        }
    }
}

internal class SvgAttribute(val name: String, var value: String, var important: Boolean)

internal class SvgNode(val doc: SvgDoc, val tag: String?, val text: String?) {
    var parent: SvgNode? = null
    val children = ArrayList<SvgNode>()
    val attrs = ArrayList<SvgAttribute>()
    var mutableText: String? = text

    val isElement get() = tag != null
    val isText get() = tag == null && mutableText != null

    fun rawAttr(name: String): String? = attrs.firstOrNull { it.name == name }?.value
    fun hasAttr(name: String) = attrs.any { it.name == name }

    fun attr(name: String): String? {
        if (doc.circular.contains(this to name)) return null
        return attrImpl(name)
    }

    fun attrImpl(name: String): String? {
        val v = rawAttr(name) ?: return null
        if (v == "none" && name in SvgDoc.POSSIBLE_NONE) return null
        return v
    }

    fun tryAttr(name: String): String? {
        if (doc.circular.contains(this to name)) return null
        return rawAttr(name)
    }

    val elementId: String get() = rawAttr("id") ?: ""

    fun parentElement(): SvgNode? { var p = parent; while (p != null) { if (p.isElement) return p; p = p.parent }; return null }
    fun ancestors(): Sequence<SvgNode> = generateSequence(this) { it.parent }

    fun findAttrNode(name: String): SvgNode? {
        if (SvgDoc.isInheritable(name)) {
            for (n in ancestors()) if (n.hasAttr(name)) return n
            return null
        }
        if (hasAttr(name)) return this
        val p = parentElement() ?: return null
        return if (p.hasAttr(name)) p else null
    }

    fun findAttr(name: String): String? {
        if (doc.circular.contains(this to name)) return null
        return findAttrNode(name)?.attrImpl(name)
    }

    fun nodeAttr(name: String): SvgNode? {
        val v = attr(name) ?: return null
        val id = (if (name == "href") SvgSyntax.iri(v) else SvgSyntax.funcIri(v)) ?: return null
        return doc.byId(id)
    }

    fun textContent(): String {
        if (!isElement) return mutableText ?: ""
        val f = children.firstOrNull()
        return if (f != null && f.isText) f.mutableText ?: "" else ""
    }

    fun descendants(): Sequence<SvgNode> = sequence {
        yield(this@SvgNode)
        for (c in children) yieldAll(c.descendants())
    }

    fun hrefIter(): Sequence<SvgNode> = sequence {
        yield(this@SvgNode)
        val visited = HashSet<SvgNode>()
        visited.add(this@SvgNode)
        var cur = this@SvgNode
        while (true) {
            val link = cur.nodeAttr("href") ?: break
            if (!visited.add(link)) break
            yield(link)
            cur = link
        }
    }

    fun firstElementChild(): SvgNode? = children.firstOrNull { it.isElement }
}

internal class SvgDoc private constructor() {
    val root = SvgNode(this, null, null)
    private val links = HashMap<String, SvgNode>()
    val circular = HashSet<Pair<SvgNode, String>>()

    fun byId(id: String): SvgNode? = links[id]
    fun rootElement(): SvgNode? = root.firstElementChild()

    companion object {
        val ELEMENTS = setOf(
            "a", "circle", "clipPath", "defs", "ellipse", "feBlend", "feColorMatrix", "feComponentTransfer", "feComposite", "feConvolveMatrix",
            "feDiffuseLighting", "feDisplacementMap", "feDistantLight", "feDropShadow", "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR",
            "feGaussianBlur", "feImage", "feMerge", "feMergeNode", "feMorphology", "feOffset", "fePointLight", "feSpecularLighting", "feSpotLight",
            "feTile", "feTurbulence", "filter", "g", "image", "line", "linearGradient", "marker", "mask", "path", "pattern", "polygon", "polyline",
            "radialGradient", "rect", "stop", "style", "svg", "switch", "symbol", "text", "textPath", "tref", "tspan", "use"
        )
        val ATTRIBUTES = ("alignment-baseline amplitude azimuth background-color baseFrequency baseline-shift bias class clip clip-path clip-rule " +
            "clipPathUnits color color-interpolation color-interpolation-filters color-profile color-rendering cx cy d diffuseConstant direction " +
            "display divisor dominant-baseline dx dy edgeMode elevation enable-background exponent fill fill-opacity fill-rule filter filterUnits " +
            "flood-color flood-opacity font font-family font-feature-settings font-kerning font-optical-sizing font-size font-size-adjust " +
            "font-stretch font-style font-synthesis font-variant font-variant-caps font-variant-east-asian font-variant-ligatures " +
            "font-variant-numeric font-variant-position font-variation-settings font-weight fr fx fy glyph-orientation-horizontal " +
            "glyph-orientation-vertical gradientTransform gradientUnits height href id image-rendering in in2 inline-size intercept isolation " +
            "k1 k2 k3 k4 kernelMatrix kernelUnitLength kerning lengthAdjust letter-spacing lighting-color limitingConeAngle line-height " +
            "marker-end marker-mid marker-start markerHeight markerUnits markerWidth mask mask-border mask-border-mode mask-border-outset " +
            "mask-border-repeat mask-border-slice mask-border-source mask-border-width mask-clip mask-composite mask-image mask-mode " +
            "mask-origin mask-position mask-size mask-type maskContentUnits maskUnits mix-blend-mode mode numOctaves offset opacity operator " +
            "order orient overflow paint-order path pathLength patternContentUnits patternTransform patternUnits points pointsAtX pointsAtY " +
            "pointsAtZ preserveAlpha preserveAspectRatio primitiveUnits r radius refX refY requiredExtensions requiredFeatures result rotate " +
            "rx ry scale seed shape-image-threshold shape-inside shape-margin shape-padding shape-rendering shape-subtract side slope space " +
            "specularConstant specularExponent spreadMethod startOffset stdDeviation stitchTiles stop-color stop-opacity stroke " +
            "stroke-dasharray stroke-dashoffset stroke-linecap stroke-linejoin stroke-miterlimit stroke-opacity stroke-width style " +
            "surfaceScale systemLanguage tableValues targetX targetY text-align text-align-last text-anchor text-decoration " +
            "text-decoration-color text-decoration-fill text-decoration-line text-decoration-stroke text-decoration-style text-indent " +
            "text-orientation text-overflow text-rendering text-underline-position textLength transform transform-box transform-origin type " +
            "unicode-bidi unicode-range values vector-effect viewBox visibility white-space width word-spacing writing-mode x x1 x2 " +
            "xChannelSelector y y1 y2 yChannelSelector z").split(' ').toHashSet()
        val PRESENTATION = ("alignment-baseline baseline-shift background-color clip-path clip-rule color color-interpolation " +
            "color-interpolation-filters color-rendering direction display dominant-baseline fill fill-opacity fill-rule filter flood-color " +
            "flood-opacity font-family font-kerning font-optical-sizing font-size font-size-adjust font-stretch font-style font-variant " +
            "font-weight font-variation-settings glyph-orientation-horizontal glyph-orientation-vertical image-rendering isolation " +
            "letter-spacing lighting-color marker-end marker-mid marker-start mask mask-type mix-blend-mode opacity overflow paint-order " +
            "shape-rendering stop-color stop-opacity stroke stroke-dasharray stroke-dashoffset stroke-linecap stroke-linejoin " +
            "stroke-miterlimit stroke-opacity stroke-width text-anchor text-decoration text-overflow text-rendering transform transform-origin " +
            "unicode-bidi vector-effect visibility white-space word-spacing writing-mode").split(' ').toHashSet()
        private val NON_INHERITABLE = ("alignment-baseline baseline-shift clip-path display filter flood-color flood-opacity mask opacity " +
            "overflow lighting-color stop-color stop-opacity text-decoration transform transform-origin").split(' ').toHashSet()
        private val ALLOWS_INHERIT = ("alignment-baseline baseline-shift clip-path clip-rule color color-interpolation " +
            "color-interpolation-filters direction display dominant-baseline fill fill-opacity fill-rule filter flood-color flood-opacity " +
            "font-family font-kerning font-optical-sizing font-size font-stretch font-style font-variant font-weight image-rendering kerning " +
            "letter-spacing marker-end marker-mid marker-start mask opacity overflow shape-rendering stop-color stop-opacity stroke " +
            "stroke-dasharray stroke-dashoffset stroke-linecap stroke-linejoin stroke-miterlimit stroke-opacity stroke-width text-anchor " +
            "text-decoration text-rendering visibility word-spacing writing-mode").split(' ').toHashSet()
        val POSSIBLE_NONE = setOf("mask", "marker-start", "marker-mid", "marker-end", "clip-path", "filter", "font-size-adjust", "text-decoration", "stroke", "stroke-dasharray")

        fun isInheritable(name: String) = name in PRESENTATION && name !in NON_INHERITABLE

        fun parse(text: String, maxDepth: Int = 1024): SvgDoc {
            val xml = SvgXml.parse(text)
            val doc = SvgDoc()
            val idMap = HashMap<String, XElem>()
            for (n in xml.descendants()) if (n is XElem) n.attr("id")?.let { idMap.putIfAbsent(it, n) }
            val rules = ArrayList<SvgCssRule>()
            for (n in xml.descendants()) {
                if (n is XElem && n.local == "style") {
                    val type = n.attr("type")
                    if (type == null || type.isEmpty() || type.equals("text/css", true)) {
                        SvgCss.parseInto(n.children.filterIsInstance<XText>().joinToString("") { it.text }, rules)
                    }
                }
            }
            val b = Builder(doc, rules, idMap, maxDepth)
            b.parseNode(xml, xml, doc.root, false, 0)
            val r = doc.rootElement()
            if (r == null || r.tag != "svg") throw SvgXmlException("no svg root")
            for (n in doc.root.descendants()) n.rawAttr("id")?.let { doc.links[it] = n }
            doc.findCircular()
            return doc
        }
    }

    private class Builder(val doc: SvgDoc, val rules: List<SvgCssRule>, val idMap: Map<String, XElem>, val maxDepth: Int) {
        var count = 0

        fun tagName(x: XElem): String? {
            if (x.ns != null && x.ns != SvgXml.SVG_NS) return null
            return if (x.local in ELEMENTS) x.local else null
        }

        fun append(parent: SvgNode, n: SvgNode) { n.parent = parent; parent.children.add(n) }

        fun parseNode(x: XNode, origin: XElem, parent: SvgNode, ignoreIds: Boolean, depth: Int) {
            if (depth > maxDepth) throw SvgXmlException("depth")
            if (x !is XElem) return
            var tag = tagName(x) ?: return
            if (tag == "style") return
            if (tag == "a") tag = "g"
            val node = parseElement(x, parent, tag, ignoreIds)
            when (tag) {
                "text" -> parseText(x, node)
                "use" -> parseUse(x, origin, node, depth + 1)
                else -> for (c in x.children) parseNode(c, origin, node, ignoreIds, depth + 1)
            }
        }

        fun appendAttr(parent: SvgNode, list: MutableList<SvgAttribute>, tag: String, name: String, value: String, important: Boolean): Boolean {
            if (name == "style" || name == "class") return false
            if (tag == "tspan" && name == "href") return false
            if (name in ALLOWS_INHERIT && value == "inherit") return resolveInherit(parent, list, name)
            list.add(SvgAttribute(name, value, important))
            return true
        }

        fun resolveInherit(parent: SvgNode, list: MutableList<SvgAttribute>, name: String): Boolean {
            if (isInheritable(name)) {
                val n = parent.ancestors().firstOrNull { it.hasAttr(name) }
                if (n != null) { val a = n.attrs.first { it.name == name }; list.add(SvgAttribute(name, a.value, a.important)); return true }
            } else {
                val a = parent.attrs.firstOrNull { it.name == name }
                if (a != null) { list.add(SvgAttribute(name, a.value, a.important)); return true }
            }
            val v = when (name) {
                "image-rendering", "shape-rendering", "text-rendering" -> "auto"
                "clip-path", "filter", "marker-end", "marker-mid", "marker-start", "mask", "stroke", "stroke-dasharray", "text-decoration" -> "none"
                "font-stretch", "font-style", "font-variant", "font-weight", "letter-spacing", "word-spacing" -> "normal"
                "fill", "flood-color", "stop-color" -> "black"
                "fill-opacity", "flood-opacity", "opacity", "stop-opacity", "stroke-opacity" -> "1"
                "clip-rule", "fill-rule" -> "nonzero"
                "baseline-shift" -> "baseline"
                "color-interpolation-filters" -> "linearRGB"
                "direction" -> "ltr"
                "display" -> "inline"
                "font-size" -> "medium"
                "overflow" -> "visible"
                "stroke-dashoffset" -> "0"
                "stroke-linecap" -> "butt"
                "stroke-linejoin" -> "miter"
                "stroke-miterlimit" -> "4"
                "stroke-width" -> "1"
                "text-anchor" -> "start"
                "visibility" -> "visible"
                "writing-mode" -> "lr-tb"
                else -> return false
            }
            list.add(SvgAttribute(name, v, false))
            return true
        }

        fun parseElement(x: XElem, parent: SvgNode, tag: String, ignoreIds: Boolean): SvgNode {
            val list = ArrayList<SvgAttribute>()
            var hrefIdx = -1
            for (a in x.attrs) {
                if (a.ns != null && a.ns != SvgXml.SVG_NS && a.ns != SvgXml.XLINK_NS && a.ns != SvgXml.XML_NS) continue
                val name = a.local
                if (name !in ATTRIBUTES) continue
                if (ignoreIds && name == "id") continue
                if (name == "mix-blend-mode" || name == "isolation" || name == "font-kerning") continue
                if (name == "image-rendering" && a.value in setOf("smooth", "high-quality", "crisp-edges", "pixelated")) continue
                if (name == "href") {
                    val unprefixed = a.ns == null
                    val isXlink = a.ns == SvgXml.XLINK_NS
                    if (!unprefixed && !isXlink) continue
                    if (hrefIdx >= 0) { if (unprefixed) list[hrefIdx].value = a.value; continue }
                }
                if (appendAttr(parent, list, tag, name, a.value, false) && name == "href") hrefIdx = list.size - 1
            }
            val baseSize = list.size
            fun insert(name: String, value: String, important: Boolean) {
                val idx = list.indexOfFirst { it.name == name }
                val added = appendAttr(parent, list, tag, name, value, important)
                if (added && idx >= 0) {
                    val last = list.size - 1
                    if (!list[idx].important) { val t = list[idx]; list[idx] = list[last]; list[last] = t }
                    list.removeAt(list.size - 1)
                }
            }
            fun writeDecl(d: SvgCssDeclaration) {
                when (d.name) {
                    "marker" -> { insert("marker-start", d.value, d.important); insert("marker-mid", d.value, d.important); insert("marker-end", d.value, d.important) }
                    "font" -> {
                        val sh = SvgSyntax.fontShorthand(d.value) ?: return
                        for ((n, v) in listOf("font-style" to "normal", "font-variant" to "normal", "font-weight" to "normal", "font-stretch" to "normal",
                            "line-height" to "normal", "font-size-adjust" to "none", "font-kerning" to "auto", "font-variant-caps" to "normal",
                            "font-variant-ligatures" to "normal", "font-variant-numeric" to "normal", "font-variant-east-asian" to "normal",
                            "font-variant-position" to "normal")) insert(n, v, d.important)
                        sh.stretch?.let { insert("font-stretch", it, d.important) }
                        sh.weight?.let { insert("font-weight", it, d.important) }
                        sh.variant?.let { insert("font-variant", it, d.important) }
                        sh.style?.let { insert("font-style", it, d.important) }
                        insert("font-size", sh.size, d.important)
                        insert("font-family", sh.family, d.important)
                    }
                    else -> if (d.name in ATTRIBUTES && d.name in PRESENTATION) insert(d.name, d.value, d.important)
                }
            }
            for (r in rules) if (r.selector.matches(x)) for (d in r.declarations) writeDecl(d)
            x.attr("style")?.let { st -> for (d in SvgCss.declarations(st)) writeDecl(d) }
            if (++count > 1_000_000) throw SvgXmlException("too many nodes")
            val node = SvgNode(doc, tag, null)
            node.attrs.addAll(list)
            append(parent, node)
            return node
        }

        fun resolveHref(x: XElem): XElem? {
            val v = x.attr("href") ?: x.attrNs(SvgXml.XLINK_NS, "href") ?: return null
            val id = SvgSyntax.iri(v) ?: return null
            return idMap[id]
        }

        fun parseUse(x: XElem, origin: XElem, node: SvgNode, depth: Int) {
            val link = resolveHref(x) ?: return
            if (link === x || link === origin) return
            if (tagName(link) == null) return
            for (d in link.descendants().drop(1)) {
                if (d is XElem && d.local == "use" && (d.ns == null || d.ns == SvgXml.SVG_NS)) {
                    val l2 = resolveHref(d)
                    if (l2 != null && (l2 === x || l2 === link)) return
                }
            }
            parseNode(link, x, node, true, depth + 1)
        }

        fun spaceOf(n: SvgNode, def: Boolean): Boolean = when (n.rawAttr("space")) { "preserve" -> true; null -> def; else -> false }

        fun trimText(text: String, preserve: Boolean): String {
            val sb = StringBuilder(text.length)
            var prev = '0'
            for (ch in text) {
                val c = if (ch == '\r' || ch == '\n' || ch == '\t') ' ' else ch
                if (!preserve && c == ' ' && c == prev) continue
                prev = c
                sb.append(c)
            }
            return sb.toString()
        }

        fun parseText(x: XElem, node: SvgNode) {
            val space = if (node.hasAttr("space")) spaceOf(node, false) else node.ancestors().firstOrNull { it.hasAttr("space") }?.let { spaceOf(it, false) } ?: false
            parseTextImpl(x, node, space)
            trimTextNodes(node, space)
        }

        fun parseTextImpl(x: XElem, parent: SvgNode, space: Boolean) {
            for (c in x.children) {
                if (c is XText) { append(parent, SvgNode(doc, null, trimText(c.text, space))); continue }
                c as XElem
                var tag = tagName(c) ?: continue
                if (tag == "a") tag = "tspan"
                if (tag != "tspan" && tag != "tref" && tag != "textPath") continue
                if (tag == "textPath" && x.local != "text") continue
                var isTref = false
                if (tag == "tref") { tag = "tspan"; isTref = true }
                val n = parseElement(c, parent, tag, false)
                val sp = spaceOf(n, space)
                if (isTref) {
                    val href = c.attrNs(SvgXml.XLINK_NS, "href") ?: c.attr("href")
                    if (href != null) {
                        val id = SvgSyntax.iri(href)
                        val target = idMap[id]
                        if (target != null && tagName(target) != null) {
                            val t = target.descendants().filterIsInstance<XText>().joinToString("") { it.text }
                            if (t.isNotEmpty()) append(n, SvgNode(doc, null, trimText(t, sp)))
                        }
                    }
                } else parseTextImpl(c, n, sp)
            }
        }

        fun collectTextNodes(p: SvgNode, depth: Int, out: MutableList<Pair<SvgNode, Int>>) {
            for (c in p.children) if (!c.isElement) out.add(c to depth) else collectTextNodes(c, depth + 1, out)
        }

        fun trimTextNodes(textElem: SvgNode, xmlspace: Boolean) {
            val nodes = ArrayList<Pair<SvgNode, Int>>()
            collectTextNodes(textElem, 0, nodes)
            fun t(n: SvgNode) = n.mutableText ?: ""
            fun removeFirst(n: SvgNode) { n.mutableText = t(n).substring(1) }
            fun removeLast(n: SvgNode) { n.mutableText = t(n).dropLast(1) }
            if (nodes.size == 1) {
                val n = nodes[0].first
                if (!xmlspace) {
                    val s = t(n)
                    when (s.length) {
                        0 -> {}
                        1 -> if (s[0] == ' ') n.mutableText = ""
                        else -> { if (s.first() == ' ') removeFirst(n); if (t(n).last() == ' ' && s.last() == ' ') removeLast(n) }
                    }
                }
            } else if (nodes.size > 1) {
                var i = 0
                val len = nodes.size - 1
                var lastNonEmpty: SvgNode? = null
                while (i < len) {
                    var n1 = nodes[i].first
                    val d1 = nodes[i].second
                    val (n2, d2) = nodes[i + 1]
                    if (t(n1).isEmpty()) lastNonEmpty?.let { n1 = it }
                    val sp1 = spaceOf(n1.parent!!, xmlspace)
                    val sp2 = spaceOf(n2.parent!!, xmlspace)
                    val t1 = t(n1); val t2 = t(n2)
                    val c1 = t1.firstOrNull(); val c2 = t1.lastOrNull(); val c3 = t2.firstOrNull(); val c4 = t2.lastOrNull()
                    if (d1 < d2) {
                        if (c3 == ' ' && !sp2) removeFirst(n2)
                    } else {
                        if (c2 == ' ' && c2 == c3) {
                            if (!sp1 && !sp2) removeLast(n1) else if (sp1 && !sp2) removeFirst(n2)
                        }
                    }
                    val isFirst = i == 0
                    val isLast = i == len - 1
                    if (isFirst && c1 == ' ' && !sp1 && t(n1).isNotEmpty()) removeFirst(n1)
                    else if (isLast && c4 == ' ' && t(n2).isNotEmpty() && !sp2) removeLast(n2)
                    if (isLast && c2 == ' ' && t(n1).isNotEmpty() && t(n2).isEmpty() && t(n1).endsWith(" ")) removeLast(n1)
                    if (t(n1).trim().isNotEmpty()) lastNonEmpty = n1
                    i++
                }
            }
        }
    }

    private fun isResource(n: SvgNode) = n.tag in setOf("clipPath", "mask", "pattern", "filter", "marker")

    private fun findCircular() {
        val visits = HashMap<SvgNode, Int>()
        val templates = HashMap<SvgNode, SvgNode>()
        fun withContent(node: SvgNode): SvgNode {
            if (node.tag != "pattern" && node.tag != "filter") return node
            val chain = ArrayList<SvgNode>()
            var current = node
            val content: SvgNode
            while (true) {
                templates[current]?.let { content = it; break }
                if (current.children.isNotEmpty()) { content = current; break }
                templates[current] = node
                chain.add(current)
                val link = current.nodeAttr("href")
                if (link != null && link.tag == node.tag) current = link else { content = node; break }
            }
            for (c in chain) templates[c] = content
            return content
        }
        fun forEachContent(node: SvgNode, f: (SvgNode) -> Unit) {
            for (c in node.children) {
                val isDef = isResource(c) || c.tag == "defs" || (c.tag == "symbol" && node.tag != "use")
                if (!isDef) { f(c); forEachContent(c, f) }
            }
        }
        fun pushLink(n: SvgNode, aid: String, tag: String, out: MutableList<Triple<SvgNode, String, SvgNode>>) {
            val link = n.nodeAttr(aid) ?: return
            if (link.tag == tag) out.add(Triple(n, aid, link))
        }
        fun pushRefs(n: SvgNode, out: MutableList<Triple<SvgNode, String, SvgNode>>) {
            if (!n.isElement) return
            pushLink(n, "clip-path", "clipPath", out)
            pushLink(n, "mask", "mask", out)
            n.attr("filter")?.let { v -> SvgSyntax.filterValues(v)?.forEach { fv -> if (fv is SvgFilterValue.Url) n.doc.byId(fv.id)?.takeIf { it.tag == "filter" }?.let { out.add(Triple(n, "filter", it)) } } }
            for (aid in listOf("fill", "stroke")) {
                val v = n.findAttr(aid) ?: continue
                val p = SvgSyntax.paint(v)
                if (p is SvgPaintValue.FuncIri) n.doc.byId(p.id)?.takeIf { it.tag == "pattern" }?.let { out.add(Triple(n, aid, it)) }
            }
            val isShape = n.tag in setOf("rect", "circle", "ellipse", "line", "polyline", "polygon", "path")
            if (isShape && n.ancestors().none { it.tag == "clipPath" }) {
                for (aid in listOf("marker-start", "marker-mid", "marker-end")) {
                    val node = n.findAttrNode(aid) ?: continue
                    val link = node.nodeAttr(aid) ?: continue
                    if (link.tag == "marker") out.add(Triple(n, aid, link))
                }
            }
        }
        fun references(node: SvgNode): List<Triple<SvgNode, String, SvgNode>> {
            val out = ArrayList<Triple<SvgNode, String, SvgNode>>()
            when (node.tag) {
                "clipPath" -> { pushLink(node, "clip-path", "clipPath", out); forEachContent(node) { pushLink(it, "clip-path", "clipPath", out) } }
                "mask" -> { pushLink(node, "mask", "mask", out); forEachContent(node) { pushRefs(it, out) } }
                "pattern", "marker" -> forEachContent(node) { pushRefs(it, out) }
                "filter" -> for (c in node.descendants()) if (c.tag == "feImage") {
                    val link = c.nodeAttr("href")
                    if (link != null && (link.tag in setOf("circle", "ellipse", "image", "line", "path", "polygon", "polyline", "rect", "text", "use", "g", "switch", "svg"))) out.add(Triple(c, "href", link))
                }
                else -> { pushRefs(node, out); forEachContent(node) { pushRefs(it, out) } }
            }
            return out
        }
        for (start in root.descendants().filter { isResource(it) }.toList()) {
            val node = withContent(start)
            if (visits.containsKey(node)) continue
            visits[node] = 1
            val stack = ArrayDeque<Pair<SvgNode, Iterator<Triple<SvgNode, String, SvgNode>>>>()
            stack.addLast(node to references(node).iterator())
            while (stack.isNotEmpty()) {
                val (nid, it) = stack.last()
                if (it.hasNext()) {
                    val (user, aid, target) = it.next()
                    val content = withContent(target)
                    when (visits[content]) {
                        1 -> circular.add(user to aid)
                        2 -> {}
                        else -> { visits[content] = 1; stack.addLast(content to references(content).iterator()) }
                    }
                } else { visits[nid] = 2; stack.removeLast() }
            }
        }
    }
}
