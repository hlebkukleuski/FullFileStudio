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

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.Locale

class DmlSheet(
    val path: String,
    val root: DmlNode,
    val kind: Int,
    val parent: DmlSheet?,
    val clrMap: Map<String, String>
) {
    val spTree: DmlNode? get() = root.path("cSld", "spTree")
    val showMasterShapes: Boolean get() = root.bool("showMasterSp") ?: true
    val hidden: Boolean get() = root.bool("show") == false

    fun master(): DmlSheet? {
        var s: DmlSheet? = this
        var guard = 0
        while (s != null && s.kind != KIND_MASTER && guard++ < 4) s = s.parent
        return s
    }

    companion object {
        const val KIND_SLIDE = 0
        const val KIND_LAYOUT = 1
        const val KIND_MASTER = 2
        const val KIND_NOTES = 3
    }
}

class DmlPresentation(val pkg: DmlPackage) {
    var slideWidth = 9144000L
        private set
    var slideHeight = 6858000L
        private set
    val slides = ArrayList<DmlSheet>()
    val theme: DmlTheme
    val defaultTextStyle: DmlNode?
    val tableStyles: Map<String, DmlNode>
    private val sheetCache = HashMap<String, DmlSheet>()
    private val themeCache = HashMap<String, DmlTheme>()
    private val imageCache = object : android.util.LruCache<String, Any>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Any): Int = (value as? PptImage)?.byteSize?.coerceAtLeast(1) ?: 1
    }
    private val failedImages = HashSet<String>()
    val presentationPath: String
    @Volatile var chartCells: ChartCellResolver? = null
    @Volatile var spreadsheet = false

    init {
        val root = pkg.xml("_rels/.rels")
        presentationPath = root?.all("Relationship")?.firstOrNull { (it.attr("Type") ?: "").endsWith("/officeDocument") }
            ?.attr("Target")?.let { DmlPackage.normalize(it) } ?: "ppt/presentation.xml"
        val pres = pkg.xml(presentationPath)
        pres?.child("sldSz")?.let {
            it.long("cx")?.takeIf { v -> v > 0 }?.let { v -> slideWidth = v }
            it.long("cy")?.takeIf { v -> v > 0 }?.let { v -> slideHeight = v }
        }
        defaultTextStyle = pres?.child("defaultTextStyle")
        val firstMaster = pres?.child("sldMasterIdLst")?.child("sldMasterId")?.attr("r:id")?.let { pkg.relTarget(presentationPath, it) }
        theme = firstMaster?.let { themeFor(it) } ?: DmlTheme(pkg.relByType(presentationPath, "/theme")?.let { pkg.xml(it) })
        val ts = HashMap<String, DmlNode>()
        pkg.relByType(presentationPath, "/tableStyles")?.let { pkg.xml(it) }?.all("tblStyle")?.forEach { s ->
            s.attr("styleId")?.let { ts[it.uppercase(Locale.ROOT)] = s }
        }
        tableStyles = ts
        val ids = pres?.child("sldIdLst")?.all("sldId").orEmpty()
        for (id in ids) {
            val target = pkg.relTarget(presentationPath, id.attr("r:id")) ?: continue
            sheet(target, DmlSheet.KIND_SLIDE)?.let { slides.add(it) }
            if (slides.size >= 5000) break
        }
    }

    fun themeFor(masterPath: String): DmlTheme? {
        val tp = pkg.relByType(masterPath, "/theme") ?: return null
        return synchronized(themeCache) { themeCache.getOrPut(tp) { DmlTheme(pkg.xml(tp)) } }
    }

    fun themeOf(sheet: DmlSheet): DmlTheme = sheet.master()?.let { themeFor(it.path) } ?: theme

    fun themePathOf(sheet: DmlSheet): String? = sheet.master()?.let { pkg.relByType(it.path, "/theme") }

    fun sheet(path: String, kind: Int): DmlSheet? {
        synchronized(sheetCache) { sheetCache[path]?.let { return it } }
        val root = pkg.xml(path) ?: return null
        val parent = when (kind) {
            DmlSheet.KIND_SLIDE -> pkg.relByType(path, "/slideLayout")?.let { sheet(it, DmlSheet.KIND_LAYOUT) }
            DmlSheet.KIND_LAYOUT -> pkg.relByType(path, "/slideMaster")?.let { sheet(it, DmlSheet.KIND_MASTER) }
            DmlSheet.KIND_NOTES -> pkg.relByType(path, "/notesMaster")?.let { sheet(it, DmlSheet.KIND_MASTER) }
            else -> null
        }
        val clr = HashMap<String, String>()
        if (kind == DmlSheet.KIND_MASTER) {
            root.child("clrMap")?.attrs?.let { clr.putAll(it) }
        } else {
            parent?.clrMap?.let { clr.putAll(it) }
            root.path("clrMapOvr", "overrideClrMapping")?.attrs?.let { clr.putAll(it) }
        }
        val s = DmlSheet(path, root, kind, parent, clr)
        synchronized(sheetCache) { sheetCache[path] = s }
        return s
    }

    fun notesText(slide: DmlSheet): String {
        val notesPath = pkg.relByType(slide.path, "/notesSlide") ?: return ""
        val root = pkg.xml(notesPath) ?: return ""
        val sb = StringBuilder()
        for (sp in root.path("cSld", "spTree")?.descendants("sp").orEmpty()) {
            val ph = sp.path("nvSpPr", "nvPr", "ph")
            if (ph?.attr("type") != "body") continue
            val t = paragraphsText(sp.child("txBody"))
            if (t.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t.trim())
            }
        }
        return sb.toString()
    }

    fun slideText(index: Int): String {
        val slide = slides.getOrNull(index) ?: return ""
        val sb = StringBuilder()
        fun walk(node: DmlNode?, depth: Int) {
            if (node == null || depth > 32) return
            for (c in node.children) {
                when (c.name) {
                    "sp" -> {
                        if (c.path("nvSpPr", "cNvPr")?.bool("hidden") == true) continue
                        val t = paragraphsText(c.child("txBody")).trim()
                        if (t.isNotEmpty()) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(t) }
                    }
                    "grpSp" -> walk(c, depth + 1)
                    "graphicFrame" -> {
                        val tbl = c.path("graphic", "graphicData", "tbl")
                        if (tbl != null) {
                            for (tr in tbl.all("tr")) {
                                val cells = tr.all("tc").map { paragraphsText(it.child("txBody")).replace('\n', ' ').trim() }
                                if (cells.any { it.isNotEmpty() }) {
                                    if (sb.isNotEmpty()) sb.append('\n')
                                    sb.append(cells.joinToString("\t"))
                                }
                            }
                        }
                    }
                }
            }
        }
        walk(slide.spTree, 0)
        return sb.toString()
    }

    fun paragraphsText(txBody: DmlNode?): String {
        if (txBody == null) return ""
        return txBody.all("p").joinToString("\n") { p ->
            p.children.joinToString("") {
                when (it.name) {
                    "r", "fld" -> it.child("t")?.text ?: ""
                    "br" -> "\n"
                    else -> ""
                }
            }
        }
    }

    internal fun image(sheetPath: String, relId: String?): PptImage? {
        val target = pkg.relTarget(sheetPath, relId) ?: return null
        return imageAt(target)
    }

    internal fun imageAt(target: String): PptImage? {
        synchronized(imageCache) {
            (imageCache.get(target) as? PptImage)?.let { return it }
            if (target in failedImages) return null
        }
        val bytes = pkg.bytes(target)
        val img = bytes?.let { decodeImage(it) }
        synchronized(imageCache) {
            if (img != null) imageCache.put(target, img) else failedImages.add(target)
        }
        return img
    }

    fun clearCaches() {
        synchronized(imageCache) { imageCache.evictAll() }
    }

    companion object {
        internal fun decodeImage(bytes: ByteArray): PptImage? {
            if (MetafileRenderer.isEmf(bytes) || MetafileRenderer.isWmf(bytes)) {
                MetafileRenderer.decode(bytes)?.let { return PptImage(null, it) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sample = 1
                while (bounds.outWidth / sample > 3072 || bounds.outHeight / sample > 3072) sample *= 2
                val bmp: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bmp != null) return PptImage(bmp, null)
            }
            if (bytes.size > 4 && ((bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()) || (bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte()))) {
                TiffDecoder.decodeFromBytes(bytes)?.let { return PptImage(it, null) }
            }
            return EmbeddedImageScanner.decodeMetafileOrSvgBitmap(bytes)?.let { PptImage(it, null) }
        }
    }
}
