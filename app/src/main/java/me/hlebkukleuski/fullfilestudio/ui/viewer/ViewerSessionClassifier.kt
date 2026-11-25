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

package me.hlebkukleuski.fullfilestudio.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.ui.reader.ProxyDocumentSession

@Immutable
data class ViewerSessionFlags(
    val proxyExt: String,
    val sessionName: String,
    val is3D: Boolean,
    val isHexSession: Boolean,
    val isPdfOrFontSession: Boolean,
    val isHtmlSession: Boolean,
    val isGraphicSession: Boolean,
    val isPresentation: Boolean,
    val isSpreadsheet: Boolean,
    val isTableBoundedLayout: Boolean,
    val isPagedWordDocument: Boolean,
    val isTextReflowCandidate: Boolean,
    val isHorizontalLayout: Boolean
)

@Composable
fun rememberViewerSessionFlags(
    session: DocumentSession,
    isDetectedHexContent: Boolean
): ViewerSessionFlags {
    val proxyExt = remember(session) {
        (session as? ProxyDocumentSession)?.extension?.lowercase(Locale.ROOT)?.trim() ?: ""
    }
    val sessionName = remember(session) { session.javaClass.name }
    val is3D = remember(session) { session.is3dContent }

    val isHexSession = remember(session, proxyExt, sessionName, isDetectedHexContent) {
        proxyExt in setOf("hex", "bin", "elf", "dex", "so") ||
            sessionName.contains("Hex", ignoreCase = true) ||
            isDetectedHexContent
    }

    val isPdfOrFontSession = remember(session, proxyExt, sessionName) {
        proxyExt in setOf("pdf", "djvu", "djv", "ttf", "otf", "ttc", "woff", "woff2", "dfont") ||
            sessionName.contains("Pdf", ignoreCase = true) ||
            sessionName.contains("Djvu", ignoreCase = true) ||
            sessionName.contains("Font", ignoreCase = true)
    }

    val isHtmlSession = remember(session, proxyExt, sessionName) {
        val firstPageHead = try {
            session.getPageText(0).take(500).lowercase(Locale.ROOT)
        } catch (_: Throwable) {
            ""
        }
        proxyExt in setOf("html", "htm", "xhtml", "mhtml", "mht") ||
            sessionName.contains("Mhtml", ignoreCase = true) ||
            sessionName.contains("Html", ignoreCase = true) ||
            firstPageHead.contains("<html") ||
            firstPageHead.contains("<!doctype html") ||
            firstPageHead.contains("content-type: multipart/related") ||
            firstPageHead.contains("content-type: text/html")
    }

    val isGraphicSession = remember(session, proxyExt, sessionName) {
        proxyExt in setOf(
            "png", "jpg", "jpeg", "jpe", "jfif", "webp", "gif", "bmp", "wbmp",
            "tiff", "tif", "dng", "cr2", "nef", "arw",
            "heic", "heif", "hif", "avif", "avifs", "ico", "cur"
        ) || sessionName.contains("ImageRegion", ignoreCase = true)
    }

    val isPresentation = remember(session, proxyExt, sessionName) {
        session.isPresentationContent ||
            proxyExt in setOf("pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp", "otp") ||
            sessionName.contains("Ppt", ignoreCase = true) ||
            sessionName.contains("Slide", ignoreCase = true)
    }

    val isSpreadsheet = remember(session, proxyExt, sessionName) {
        proxyExt in setOf(
            "xlsx", "xlsm", "xlsb", "xltx", "xltm", "xls", "xlt", "csv", "tsv", "ods", "ots",
            "db", "db3", "s3db", "sl3", "sqlite", "sqlite3", "accdb", "mdb", "dbf", "parquet", "duckdb", "realm", "bson", "jsonl", "ndjson"
        ) ||
            sessionName.contains("Sheet", ignoreCase = true) ||
            sessionName.contains("Xls", ignoreCase = true) ||
            sessionName.contains("Csv", ignoreCase = true) ||
            sessionName.contains("Database", ignoreCase = true) ||
            sessionName.contains("Sqlite", ignoreCase = true) ||
            sessionName.contains("Dbf", ignoreCase = true) ||
            sessionName.contains("Parquet", ignoreCase = true) ||
            sessionName.contains("Columnar", ignoreCase = true)
    }

    val isPagedWordDocument = remember(session, proxyExt, sessionName) {
        (proxyExt in setOf("doc", "dot", "docx", "docm", "dotx", "dotm") || sessionName.contains("DocSession", ignoreCase = true)) &&
            try {
                session.getDocumentWidth() > 0f && session.getDocumentHeight() > 0f
            } catch (_: Throwable) {
                false
            }
    }

    val isTextReflowCandidate = remember(
        session,
        proxyExt,
        sessionName,
        isPresentation,
        isSpreadsheet,
        isHexSession,
        isPdfOrFontSession,
        isPagedWordDocument,
        is3D
    ) {
        val isTextExt = proxyExt in setOf(
            "fb2", "fbz", "epub", "azw3", "azw", "mobi", "prc", "one",
            "txt", "docx", "docm", "dotx", "dotm", "doc", "dot", "odt", "ott", "rtf",
            "json", "xml", "md", "ini", "cfg", "log", "html", "htm", "xhtml", "mhtml", "mht",
            "yaml", "yml", "py", "js", "ts", "kt", "java", "c", "cpp",
            "h", "hpp", "sh", "sql", "css", "properties", "toml", "gradle"
        )
        !isPagedWordDocument && (
            isTextExt ||
                sessionName.contains("Ebook", ignoreCase = true) ||
                sessionName.contains("PlainText", ignoreCase = true) ||
                sessionName.contains("DocSession", ignoreCase = true) ||
                sessionName.contains("Rtf", ignoreCase = true) ||
                sessionName.contains("OneNote", ignoreCase = true)
            ) &&
            !isPresentation && !isSpreadsheet && !is3D && !isHexSession && !isPdfOrFontSession
    }

    return remember(
        proxyExt,
        sessionName,
        is3D,
        isHexSession,
        isPdfOrFontSession,
        isHtmlSession,
        isGraphicSession,
        isPresentation,
        isSpreadsheet,
        isPagedWordDocument,
        isTextReflowCandidate
    ) {
        ViewerSessionFlags(
            proxyExt = proxyExt,
            sessionName = sessionName,
            is3D = is3D,
            isHexSession = isHexSession,
            isPdfOrFontSession = isPdfOrFontSession,
            isHtmlSession = isHtmlSession,
            isGraphicSession = isGraphicSession,
            isPresentation = isPresentation,
            isSpreadsheet = isSpreadsheet,
            isTableBoundedLayout = isSpreadsheet || isHexSession,
            isPagedWordDocument = isPagedWordDocument,
            isTextReflowCandidate = isTextReflowCandidate,
            isHorizontalLayout = isPresentation
        )
    }
}

fun extractSlideSpeakerNotes(currentPageNativeText: String, isPresentation: Boolean): String {
    return if (isPresentation && currentPageNativeText.contains("\n\n[Notes]\n")) {
        currentPageNativeText.substringAfter("\n\n[Notes]\n")
            .replace(SLIDE_META_TAG_REGEX, "")
            .trim()
    } else {
        ""
    }
}

fun stripSlideNotesAndMeta(currentPageNativeText: String): String {
    val withoutNotes = if (currentPageNativeText.contains("\n\n[Notes]\n")) {
        currentPageNativeText.substringBefore("\n\n[Notes]\n")
    } else {
        currentPageNativeText
    }
    return withoutNotes.replace(SLIDE_META_TAG_REGEX, "").trim()
}

internal fun copyViewerSelectionToClipboard(
    session: DocumentSession,
    clipManager: ClipboardManager?,
    coroutineScope: CoroutineScope,
    useReflowLayout: Boolean,
    isSpreadsheet: Boolean,
    fullPageTextForReflow: String,
    textSelStart: Int,
    textSelEnd: Int,
    sheetSelStartRow: Int,
    sheetSelStartCol: Int,
    sheetSelEndRow: Int,
    sheetSelEndCol: Int,
    ocrChars: List<RecognizedCharUi>,
    selStartChar: Int,
    selEndChar: Int,
    cleanCurrentPageNativeText: String,
    onClearSelections: () -> Unit
) {
    if (session.isCopyRestricted) {
        onClearSelections()
        return
    }
    if (useReflowLayout && fullPageTextForReflow.isNotEmpty() && textSelStart >= 0 && textSelEnd >= 0) {
        val minI = minOf(textSelStart, textSelEnd).coerceIn(0, fullPageTextForReflow.length - 1)
        val maxI = (maxOf(textSelStart, textSelEnd) + 1).coerceIn(minI, fullPageTextForReflow.length)
        val copied = fullPageTextForReflow.substring(minI, maxI)
        if (copied.isNotEmpty()) {
            clipManager?.setPrimaryClip(ClipData.newPlainText("Selected Text", copied))
        }
    } else if (!useReflowLayout && isSpreadsheet && sheetSelStartRow >= 0 && sheetSelStartCol >= 0) {
        val r0 = minOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(0)
        val c0 = minOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(0)
        val r1 = maxOf(sheetSelStartRow, sheetSelEndRow).coerceAtLeast(r0)
        val c1 = maxOf(sheetSelStartCol, sheetSelEndCol).coerceAtLeast(c0)
        coroutineScope.launch(Dispatchers.IO) {
            val copied = try {
                session.getSelectedRangeText(r0, c0, r1, c1)
            } catch (_: Exception) {
                ""
            }
            if (copied.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    clipManager?.setPrimaryClip(ClipData.newPlainText("Selected Range", copied))
                }
            }
        }
    } else if (!useReflowLayout && ocrChars.isNotEmpty() && selStartChar >= 0 && selEndChar >= 0) {
        val minI = minOf(selStartChar, selEndChar).coerceIn(0, ocrChars.size - 1)
        val maxI = maxOf(selStartChar, selEndChar).coerceIn(0, ocrChars.size - 1)
        val copied = buildString {
            var lastLine = -1
            var prevRight = -1f
            var prevWasSpace = false
            for (i in minI..maxI) {
                val ch = ocrChars[i]
                val isSpaceToken = ch.text.isBlank()
                if (lastLine != -1 && ch.lineIndex != lastLine) {
                    append('\n')
                    prevRight = -1f
                    prevWasSpace = true
                } else if (lastLine == ch.lineIndex &&
                    prevRight >= 0f &&
                    !prevWasSpace &&
                    !isSpaceToken
                ) {
                    val avgH = ch.rect.height().coerceAtLeast(8f)
                    if (ch.rect.left - prevRight > avgH * 0.30f) {
                        append(' ')
                        prevWasSpace = true
                    }
                }
                if (isSpaceToken) {
                    if (!prevWasSpace && isNotEmpty()) {
                        append(' ')
                        prevWasSpace = true
                    }
                } else {
                    append(ch.text)
                    prevWasSpace = false
                }
                lastLine = ch.lineIndex
                prevRight = ch.rect.right
            }
        }.trim()
        if (copied.isNotEmpty()) {
            clipManager?.setPrimaryClip(ClipData.newPlainText("Selected Text", copied))
        }
    } else if (!useReflowLayout && cleanCurrentPageNativeText.isNotBlank()) {
        clipManager?.setPrimaryClip(ClipData.newPlainText("Page Text", cleanCurrentPageNativeText))
    }
    onClearSelections()
}
