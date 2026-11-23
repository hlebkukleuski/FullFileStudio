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

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object OfficeFonts {
    @Volatile
    var loader: ((String) -> Typeface?)? = null

    @Volatile
    var bytesLoader: ((String) -> ByteArray?)? = null

    private val cache = ConcurrentHashMap<String, Typeface>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun install(assets: AssetManager) {
        loader = { name -> createTypefaceWithFallback(assets, name) }
        bytesLoader = { name -> assets.open("fonts/office/$name").use { it.readBytes() } }
        DmlAssets.loader = { name -> assets.open(name).use { it.readBytes() } }
        cache.clear()
        missing.clear()
    }

    internal fun fallbackAssetFileName(primaryFile: String): String? {
        val lower = primaryFile.lowercase(Locale.ROOT)
        val suffix = when {
            lower.contains("bolditalic") || lower.contains("boldoblique") -> "BoldItalic"
            lower.contains("bold") || lower == "selawkb.ttf" -> "Bold"
            lower.contains("italic") || lower.contains("oblique") -> "Italic"
            else -> "Regular"
        }
        return when {
            lower.startsWith("caladea-") ||
                lower.startsWith("gelasio-") ||
                lower.startsWith("texgyrepagella-") ||
                lower.startsWith("texgyrebonum-") ||
                lower.startsWith("texgyreschola-") -> "LiberationSerif-$suffix.ttf"
            lower.startsWith("comicneue-") ||
                lower.startsWith("texgyreadventor-") ||
                lower.startsWith("texgyreheroscn-") ||
                lower.startsWith("selawk") -> "LiberationSans-$suffix.ttf"
            else -> null
        }
    }

    private fun createTypefaceWithFallback(assets: AssetManager, primaryFile: String): Typeface {
        val fallbackFile = fallbackAssetFileName(primaryFile)
        if (fallbackFile != null) {
            try {
                val lower = primaryFile.lowercase(Locale.ROOT)
                val weight = if (lower.contains("bold") || lower == "selawkb.ttf") {
                    FontStyle.FONT_WEIGHT_BOLD
                } else {
                    FontStyle.FONT_WEIGHT_NORMAL
                }
                val slant = if (lower.contains("italic") || lower.contains("oblique")) {
                    FontStyle.FONT_SLANT_ITALIC
                } else {
                    FontStyle.FONT_SLANT_UPRIGHT
                }
                val primaryFont = Font.Builder(assets, "fonts/office/$primaryFile")
                    .setWeight(weight)
                    .setSlant(slant)
                    .build()
                val fallbackFont = Font.Builder(assets, "fonts/office/$fallbackFile")
                    .setWeight(weight)
                    .setSlant(slant)
                    .build()
                return Typeface.CustomFallbackBuilder(FontFamily.Builder(primaryFont).build())
                    .addCustomFallback(FontFamily.Builder(fallbackFont).build())
                    .setStyle(FontStyle(weight, slant))
                    .build()
            } catch (_: Throwable) {
            }
        }
        return Typeface.createFromAsset(assets, "fonts/office/$primaryFile")
    }

    fun clearCache() {
        cache.clear()
        missing.clear()
    }

    private val SERIF_METRIC = setOf(
        "times new roman", "times", "tinos", "liberation serif", "times new roman cyr", "thorndale"
    )
    private val SANS_METRIC = setOf(
        "arial", "arial black", "helvetica", "arimo", "liberation sans",
        "arial cyr", "albany", "helvetica neue", "arial unicode ms",
        "nimbus sans", "microsoft sans serif"
    )
    private val MONO_METRIC = setOf(
        "courier new", "courier", "cousine", "liberation mono", "cumberland"
    )
    private val CALIBRI_METRIC = setOf(
        "calibri", "carlito", "calibri light", "aptos", "corbel"
    )
    private val CAMBRIA_METRIC = setOf(
        "cambria", "caladea", "cambria math", "constantia"
    )
    private val PAGELLA_METRIC = setOf(
        "book antiqua", "palatino linotype", "palatino", "tex gyre pagella", "urw palladio l"
    )
    private val ADVENTOR_METRIC = setOf(
        "century gothic", "avant garde", "itc avant garde gothic", "tex gyre adventor", "urw gothic"
    )
    private val BONUM_METRIC = setOf(
        "bookman old style", "bookman", "itc bookman", "tex gyre bonum", "urw bookman"
    )
    private val SCHOLA_METRIC = setOf(
        "century schoolbook", "century", "new century schoolbook", "tex gyre schola"
    )
    private val NARROW_METRIC = setOf(
        "arial narrow", "helvetica narrow", "liberation sans narrow", "tex gyre heros cn"
    )
    private val GELASIO_METRIC = setOf(
        "georgia", "gelasio"
    )
    private val GARAMOND_METRIC = setOf(
        "garamond", "eb garamond", "eb garamond 12"
    )
    private val COMIC_METRIC = setOf(
        "comic sans ms", "comic sans", "comic neue"
    )
    private val SELAWIK_METRIC = setOf(
        "segoe ui", "segoe ui light", "segoe ui semibold", "selawik",
        "candara", "trebuchet ms", "franklin gothic book", "franklin gothic medium", "gill sans mt"
    )
    private val DEJAVU_SANS_METRIC = setOf(
        "verdana", "tahoma", "lucida sans unicode", "dejavu sans", "impact",
        "symbol", "webdings", "wingdings", "wingdings 2", "wingdings 3"
    )
    private val DEJAVU_MONO_METRIC = setOf(
        "consolas", "lucida console", "dejavu sans mono"
    )

    fun bundledFamily(fontName: String?): String? {
        val n = fontName?.trim()?.lowercase(Locale.ROOT)
            ?.removeSuffix(" (body)")?.removeSuffix(" (headings)") ?: return null
        return when {
            n in SERIF_METRIC -> "LiberationSerif"
            n in SANS_METRIC -> "LiberationSans"
            n in MONO_METRIC -> "LiberationMono"
            n in CALIBRI_METRIC -> "Carlito"
            n in CAMBRIA_METRIC -> "Caladea"
            n in PAGELLA_METRIC -> "TeXGyrePagella"
            n in ADVENTOR_METRIC -> "TeXGyreAdventor"
            n in BONUM_METRIC -> "TeXGyreBonum"
            n in SCHOLA_METRIC -> "TeXGyreSchola"
            n in NARROW_METRIC -> "TeXGyreHerosCn"
            n in GELASIO_METRIC -> "Gelasio"
            n in GARAMOND_METRIC -> "EBGaramond"
            n in COMIC_METRIC -> "ComicNeue"
            n in SELAWIK_METRIC -> "Selawik"
            n in DEJAVU_SANS_METRIC -> "DejaVuSans"
            n in DEJAVU_MONO_METRIC -> "DejaVuSansMono"
            else -> null
        }
    }

    fun assetFileName(fontName: String?, bold: Boolean, italic: Boolean): String? {
        val family = bundledFamily(fontName) ?: return null
        val ttfSuffix = when {
            bold && italic -> "BoldItalic"
            bold -> "Bold"
            italic -> "Italic"
            else -> "Regular"
        }
        return when (family) {
            "LiberationSerif", "LiberationSans", "LiberationMono",
            "Carlito", "Caladea", "Gelasio", "EBGaramond", "ComicNeue" -> "$family-$ttfSuffix.ttf"
            "TeXGyrePagella" -> "texgyrepagella-${ttfSuffix.lowercase(Locale.ROOT)}.otf"
            "TeXGyreAdventor" -> "texgyreadventor-${ttfSuffix.lowercase(Locale.ROOT)}.otf"
            "TeXGyreBonum" -> "texgyrebonum-${ttfSuffix.lowercase(Locale.ROOT)}.otf"
            "TeXGyreSchola" -> "texgyreschola-${ttfSuffix.lowercase(Locale.ROOT)}.otf"
            "TeXGyreHerosCn" -> "texgyreheroscn-${ttfSuffix.lowercase(Locale.ROOT)}.otf"
            "Selawik" -> if (bold) "selawkb.ttf" else "selawk.ttf"
            "DejaVuSans" -> when {
                bold && italic -> "DejaVuSans-BoldOblique.ttf"
                bold -> "DejaVuSans-Bold.ttf"
                italic -> "DejaVuSans-Oblique.ttf"
                else -> "DejaVuSans.ttf"
            }
            "DejaVuSansMono" -> when {
                bold && italic -> "DejaVuSansMono-BoldOblique.ttf"
                bold -> "DejaVuSansMono-Bold.ttf"
                italic -> "DejaVuSansMono-Oblique.ttf"
                else -> "DejaVuSansMono.ttf"
            }
            else -> null
        }
    }

    fun readFontBytes(fontName: String?, bold: Boolean = false, italic: Boolean = false): ByteArray? {
        val file = assetFileName(fontName, bold, italic) ?: return null
        val bl = bytesLoader ?: return null
        return try { bl(file) } catch (_: Throwable) { null }
    }

    fun resolve(fontName: String?, bold: Boolean, italic: Boolean): Typeface {
        typeface(fontName, bold, italic)?.let { return it }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(PptFonts.androidFamily(fontName?.let { PptFont(it, 0, 0) }), style)
    }

    fun typeface(fontName: String?, bold: Boolean, italic: Boolean): Typeface? {
        val file = assetFileName(fontName, bold, italic) ?: return null
        cache[file]?.let { return it }
        if (file in missing) return null
        val load = loader ?: return null
        val tf = try { load(file) } catch (_: Throwable) { null }
        if (tf == null) missing.add(file) else cache[file] = tf
        return tf
    }
}
