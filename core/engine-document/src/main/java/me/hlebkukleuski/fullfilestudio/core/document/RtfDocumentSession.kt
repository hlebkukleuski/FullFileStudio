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
 * Implements OLE2 Compound File Binary ([MS-CFB]), Excel BIFF8/BIFF12 ([MS-XLS] / [MS-XLSB]),
 * Word Binary ([MS-DOC]), PowerPoint Binary ([MS-PPT]), OneNote ([MS-ONE] / [MS-ONESTORE])
 * under the Microsoft Open Specifications Promise (OSP), ISO-9660 (ECMA-119), and dBase DBF,
 * with BIFF8/OLE2 structure definitions informed by Apache POI (https://poi.apache.org/,
 * Copyright 2003-2026 The Apache Software Foundation, Apache-2.0).
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class RtfDocumentSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    @Volatile private var lastErrorMsg: String? = null
    private val pages = mutableListOf<String>()
    private val embeddedImages = mutableListOf<Bitmap>()
    private var fullText = ""
    private val textPaint = TextPaint().apply {
        color = Color.DKGRAY
        textSize = 24f
        typeface = Typeface.SANS_SERIF
        isAntiAlias = true
    }

    init {
        try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val size = minOf(source.size, 24 * 1024 * 1024L).toInt()
            if (size <= 0) {
                lastErrorMsg = "RTF file is empty."
            } else {
                val buf = ByteBuffer.allocateDirect(size)
                val r = source.readAt(0L, buf)
                val bytes = ByteArray(r.coerceAtLeast(0))
                if (r > 0) {
                    buf.flip()
                    buf.get(bytes)
                }
                if (bytes.size < 5 || !String(bytes, 0, 5, StandardCharsets.US_ASCII).startsWith("{\\rtf")) {
                    lastErrorMsg = "File lacks RTF header ({\\rtf)."
                } else {
                    fullText = parseRtfBytes(bytes, embeddedImages)
                }
            }
        } catch (e: Exception) {
            lastErrorMsg = "RTF read error: ${e.localizedMessage}"
        }
        if (fullText.isBlank() && embeddedImages.isEmpty() && lastErrorMsg == null) {
            lastErrorMsg = "RTF document contains no visible text."
        }
        EbookTextSession.paginateRichText(fullText, embeddedImages.size, pages)
    }

    companion object {
        private const val MAX_RTF_GROUP_DEPTH = 512
        private const val MAX_PICT_HEX_CHARS = 16 * 1024 * 1024
        private const val MAX_RTF_TEXT_CHARS = 8_000_000

        private fun charsetForRtfFCharset(fcharsetCode: Int): Charset? {
            val name = when (fcharsetCode) {
                0 -> "windows-1252"
                128 -> "Shift_JIS"
                129 -> "EUC-KR"
                134 -> "GB2312"
                136 -> "Big5"
                161 -> "windows-1253"
                162 -> "windows-1254"
                163 -> "windows-1258"
                177 -> "windows-1255"
                178 -> "windows-1256"
                186 -> "windows-1257"
                204 -> "windows-1251"
                222 -> "windows-874"
                238 -> "windows-1250"
                255 -> "CP850"
                else -> null
            } ?: return null
            return try {
                Charset.forName(name)
            } catch (_: Throwable) {
                null
            }
        }

        fun parseRtfBytes(bytes: ByteArray, outImages: MutableList<Bitmap>? = null): String {
            val out = StringBuilder()
            var defaultCharset: Charset = PlainTextSession.CP1251
            val fontCharsets = HashMap<Int, Charset>()
            val hexBuf = ByteArrayOutputStream()
            val pictHexBuilder = StringBuilder()

            data class RtfGroupState(
                var ucSkip: Int = 1,
                var ignoreContent: Boolean = false,
                var inFontTbl: Boolean = false,
                var currentFontId: Int = -1,
                var inPict: Boolean = false,
                var inNonShpPict: Boolean = false,
                var inBlipUid: Boolean = false,
                var bold: Boolean = false,
                var italic: Boolean = false,
                var underline: Boolean = false,
                var strike: Boolean = false
            )
            val stack = ArrayDeque<RtfGroupState>()
            var overflowGroupDepth = 0
            var state = RtfGroupState()

            var emittedBold = false
            var emittedItalic = false
            var emittedUnderline = false
            var emittedStrike = false

            fun activeCharset(): Charset {
                return fontCharsets[state.currentFontId] ?: defaultCharset
            }

            fun syncInlineTagsBeforeText() {
                if (!emittedBold && state.bold) {
                    out.append("<b>")
                    emittedBold = true
                } else if (emittedBold && !state.bold) {
                    out.append("</b>")
                    emittedBold = false
                }
                if (!emittedItalic && state.italic) {
                    out.append("<i>")
                    emittedItalic = true
                } else if (emittedItalic && !state.italic) {
                    out.append("</i>")
                    emittedItalic = false
                }
                if (!emittedUnderline && state.underline) {
                    out.append("<u>")
                    emittedUnderline = true
                } else if (emittedUnderline && !state.underline) {
                    out.append("</u>")
                    emittedUnderline = false
                }
                if (!emittedStrike && state.strike) {
                    out.append("<s>")
                    emittedStrike = true
                } else if (emittedStrike && !state.strike) {
                    out.append("</s>")
                    emittedStrike = false
                }
            }

            fun closeAllInlineTagsBeforeBreak() {
                if (emittedStrike) {
                    out.append("</s>")
                    emittedStrike = false
                }
                if (emittedUnderline) {
                    out.append("</u>")
                    emittedUnderline = false
                }
                if (emittedItalic) {
                    out.append("</i>")
                    emittedItalic = false
                }
                if (emittedBold) {
                    out.append("</b>")
                    emittedBold = false
                }
            }

            fun flushHexBuffer() {
                if (hexBuf.size() > 0) {
                    if (out.length < MAX_RTF_TEXT_CHARS) {
                        syncInlineTagsBeforeText()
                        out.append(String(hexBuf.toByteArray(), activeCharset()))
                    }
                    hexBuf.reset()
                }
            }

            fun decodePictHexIfPresent() {
                if (outImages != null && outImages.size < 32 && pictHexBuilder.length in 64..MAX_PICT_HEX_CHARS) {
                    try {
                        val cleanHex = pictHexBuilder.toString()
                        val rawBytes = ByteArray(cleanHex.length / 2)
                        for (k in rawBytes.indices) {
                            val hi = Character.digit(cleanHex[k * 2], 16)
                            val lo = Character.digit(cleanHex[k * 2 + 1], 16)
                            if (hi >= 0 && lo >= 0) {
                                rawBytes[k] = ((hi shl 4) or lo).toByte()
                            }
                        }
                        EmbeddedImageScanner.decodeMetafileOrSvgBitmap(rawBytes, 0, rawBytes.size)?.let {
                            closeAllInlineTagsBeforeBreak()
                            val idx = outImages.size
                            outImages.add(it)
                            if (out.isNotEmpty() && !out.endsWith('\n')) {
                                out.append('\n')
                            }
                            out.append("[IMAGE:$idx]\n")
                        }
                    } catch (_: Throwable) {}
                }
                pictHexBuilder.setLength(0)
            }

            val ignoredDestinations = setOf(
                "fonttbl", "colortbl", "stylesheet", "info", "object",
                "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf",
                "fldinst", "xmlnstbl", "listtable", "listoverridetable", "rsidtbl",
                "generator", "company", "operator", "themedata", "colorschememapping",
                "latentstyles", "datastore", "pgp", "pgptbl", "nonshppict", "sn", "sv",
                "pgdsctbl", "userprops", "ftnsep", "ftnsepc", "aftnsep"
            )

            var i = 0
            var skipAfterUnicode = 0

            while (i < bytes.size && out.length < MAX_RTF_TEXT_CHARS) {
                val b = bytes[i].toInt() and 0xFF
                val c = b.toChar()

                when (c) {
                    '{' -> {
                        flushHexBuffer()
                        if (stack.size < MAX_RTF_GROUP_DEPTH) {
                            stack.addLast(state.copy())
                        } else {
                            overflowGroupDepth++
                        }
                        i++
                    }
                    '}' -> {
                        flushHexBuffer()
                        if (overflowGroupDepth > 0) {
                            overflowGroupDepth--
                        } else {
                            val wasPict = state.inPict && !state.inNonShpPict
                            if (stack.isNotEmpty()) state = stack.removeLast()
                            if (wasPict && !state.inPict) {
                                decodePictHexIfPresent()
                            }
                        }
                        i++
                    }
                    '\\' -> {
                        i++
                        if (i >= bytes.size) break
                        val next = (bytes[i].toInt() and 0xFF).toChar()
                        when {
                            next == '\'' -> {
                                if (i + 2 < bytes.size) {
                                    val hexStr = String(bytes, i + 1, 2, StandardCharsets.US_ASCII)
                                    val byteVal = hexStr.toIntOrNull(16)
                                    if (byteVal != null) {
                                        if (skipAfterUnicode > 0) {
                                            skipAfterUnicode--
                                        } else if (!state.ignoreContent && hexBuf.size() < 262_144) {
                                            hexBuf.write(byteVal)
                                        }
                                    }
                                    i += 3
                                } else i++
                            }
                            next == '*' -> {
                                flushHexBuffer()
                                var peek = i + 1
                                while (peek < bytes.size && (bytes[peek].toInt() and 0xFF).toChar() == '\\') peek++
                                val destStart = peek
                                while (peek < bytes.size && (bytes[peek].toInt() and 0xFF).toChar().isLetter()) peek++
                                val destWord = if (peek > destStart) {
                                    String(bytes, destStart, peek - destStart, StandardCharsets.US_ASCII).lowercase(Locale.ROOT)
                                } else ""
                                if (destWord !in setOf("shppict", "shpinst", "fldrslt", "shptxt")) {
                                    state.ignoreContent = true
                                }
                                i++
                            }
                            next == '\\' || next == '{' || next == '}' -> {
                                flushHexBuffer()
                                if (skipAfterUnicode > 0) skipAfterUnicode--
                                else if (!state.ignoreContent) {
                                    syncInlineTagsBeforeText()
                                    out.append(next)
                                }
                                i++
                            }
                            next == '~' -> {
                                flushHexBuffer()
                                if (!state.ignoreContent) {
                                    syncInlineTagsBeforeText()
                                    out.append('\u00A0')
                                }
                                i++
                            }
                            next == '-' -> {
                                flushHexBuffer()
                                i++
                            }
                            next == '_' -> {
                                flushHexBuffer()
                                if (!state.ignoreContent) {
                                    syncInlineTagsBeforeText()
                                    out.append('-')
                                }
                                i++
                            }
                            next == '\n' || next == '\r' -> {
                                flushHexBuffer()
                                if (!state.ignoreContent) {
                                    closeAllInlineTagsBeforeBreak()
                                    out.append('\n')
                                }
                                i++
                            }
                            next.isLetter() -> {
                                val wordStart = i
                                while (i < bytes.size && (bytes[i].toInt() and 0xFF).toChar().isLetter()) i++
                                val word = String(bytes, wordStart, i - wordStart, StandardCharsets.US_ASCII)

                                val numStart = i
                                if (i < bytes.size && ((bytes[i].toInt() and 0xFF).toChar() == '-' || (bytes[i].toInt() and 0xFF).toChar().isDigit())) {
                                    i++
                                    while (i < bytes.size && (bytes[i].toInt() and 0xFF).toChar().isDigit()) i++
                                }
                                val paramStr = if (i > numStart) String(bytes, numStart, i - numStart, StandardCharsets.US_ASCII) else null
                                if (i < bytes.size && (bytes[i].toInt() and 0xFF).toChar() == ' ') i++

                                if (word != "u") flushHexBuffer()

                                when (word) {
                                    "fonttbl" -> {
                                        state.ignoreContent = true
                                        state.inFontTbl = true
                                    }
                                    "f" -> {
                                        val fId = paramStr?.toIntOrNull()
                                        if (fId != null) {
                                            state.currentFontId = fId
                                        }
                                    }
                                    "fcharset" -> {
                                        val fcs = paramStr?.toIntOrNull()
                                        if (fcs != null) {
                                            val resolvedCs = charsetForRtfFCharset(fcs)
                                            if (resolvedCs != null) {
                                                if (state.currentFontId >= 0) {
                                                    fontCharsets[state.currentFontId] = resolvedCs
                                                }
                                                if (fcs == 204) {
                                                    defaultCharset = resolvedCs
                                                }
                                            }
                                        }
                                    }
                                    "nonshppict" -> {
                                        state.ignoreContent = true
                                        state.inNonShpPict = true
                                    }
                                    "shppict", "shp", "shpinst", "shptxt" -> {
                                        if (!state.inNonShpPict) {
                                            state.ignoreContent = false
                                        }
                                    }
                                    "blipuid" -> {
                                        state.ignoreContent = true
                                        state.inBlipUid = true
                                    }
                                    "pict" -> {
                                        state.ignoreContent = true
                                        if (!state.inNonShpPict) {
                                            state.inPict = true
                                            state.inBlipUid = false
                                            pictHexBuilder.setLength(0)
                                        }
                                    }
                                    in ignoredDestinations -> state.ignoreContent = true
                                    "ansicpg" -> {
                                        val cp = paramStr?.toIntOrNull() ?: 1251
                                        if (cp != 1252 || fontCharsets.values.none { it == PlainTextSession.CP1251 }) {
                                            defaultCharset = try {
                                                Charset.forName("windows-$cp")
                                            } catch (_: Exception) {
                                                PlainTextSession.CP1251
                                            }
                                        }
                                    }
                                    "uc" -> state.ucSkip = (paramStr?.toIntOrNull() ?: 1).coerceIn(0, 16)
                                    "b" -> state.bold = (paramStr != "0")
                                    "i" -> state.italic = (paramStr != "0")
                                    "ul" -> state.underline = (paramStr != "0")
                                    "ulnone" -> state.underline = false
                                    "strike", "striked" -> state.strike = (paramStr != "0")
                                    "plain" -> {
                                        state.bold = false
                                        state.italic = false
                                        state.underline = false
                                        state.strike = false
                                    }
                                    "emdash" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('—') }
                                    "endash" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('–') }
                                    "bullet" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append("• ") }
                                    "ldblquote" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('“') }
                                    "rdblquote" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('”') }
                                    "lquote" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('‘') }
                                    "rquote" -> if (!state.ignoreContent) { syncInlineTagsBeforeText(); out.append('’') }
                                    "u" -> {
                                        flushHexBuffer()
                                        if (!state.ignoreContent && paramStr != null) {
                                            var codePoint = paramStr.toIntOrNull() ?: 63
                                            if (codePoint < 0) codePoint += 65536
                                            syncInlineTagsBeforeText()
                                            out.append(codePoint.toChar())
                                            skipAfterUnicode = state.ucSkip
                                        }
                                    }
                                    "par", "line", "sect", "page", "row", "nestrow" -> {
                                        if (!state.ignoreContent) {
                                            closeAllInlineTagsBeforeBreak()
                                            while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
                                                out.deleteCharAt(out.length - 1)
                                            }
                                            out.append('\n')
                                        }
                                    }
                                    "tab", "cell", "nestcell" -> {
                                        if (!state.ignoreContent) {
                                            closeAllInlineTagsBeforeBreak()
                                            out.append('\t')
                                        }
                                    }
                                }
                            }
                            else -> i++
                        }
                    }
                    '\r', '\n' -> i++
                    else -> {
                        flushHexBuffer()
                        if (state.inPict && !state.inNonShpPict && !state.inBlipUid && outImages != null && outImages.size < 32) {
                            if ((c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') && pictHexBuilder.length < MAX_PICT_HEX_CHARS) {
                                pictHexBuilder.append(c)
                            }
                        } else if (skipAfterUnicode > 0) {
                            skipAfterUnicode--
                        } else if (!state.ignoreContent && b >= 32) {
                            syncInlineTagsBeforeText()
                            out.append(c)
                        }
                        i++
                    }
                }
            }
            flushHexBuffer()
            closeAllInlineTagsBeforeBreak()
            return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        }
    }

    override val pageCount: Int get() = pages.size.coerceAtLeast(1)
    override fun getLastError(): String? = lastErrorMsg

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        if (pageIndex == -1) return fullText
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        return pages.getOrElse(cleanIdx) { "" }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        if (lastErrorMsg != null) {
            bitmap.eraseColor(if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
            return
        }
        val isIllustrationOnly = (pageIndex and ILLUSTRATION_ONLY_PAGE_FLAG) != 0
        val cleanIdx = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val effectivePageIndex = if (isIllustrationOnly) {
            (pageIndex and LIGHT_THEME_PAGE_FLAG.inv())
        } else {
            cleanIdx
        }
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 5f)
        val canvas = Canvas(bitmap)

        EbookTextSession.renderRichDocumentPageToCanvas(
            canvas = canvas,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            pageIndex = effectivePageIndex,
            tileX = tileX,
            tileY = tileY,
            zoom = zoom,
            isLightTheme = isLightTheme,
            pageText = getPageText(cleanIdx),
            embeddedImages = embeddedImages,
            baseTextPaint = textPaint
        )
    }

    @Synchronized
    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        val start = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        for (i in start until pages.size) if (pages[i].contains(query, true)) return i.toLong()
        for (i in 0 until start) if (pages[i].contains(query, true)) return i.toLong()
        return -1L
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        embeddedImages.forEach { if (!it.isRecycled) it.recycle() }
        embeddedImages.clear()
        try { pfd.close() } catch (_: Exception) {}
    }
}
