/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

object MhtmlParser {

    data class MhtmlResult(
        val htmlContent: String,
        val plainText: String,
        val embeddedImages: List<Bitmap>
    )

    fun parse(stream: ByteArray): MhtmlResult {
        val fullContent = String(stream, StandardCharsets.UTF_8)
        var boundary: String? = null

        val boundaryMatch = Regex("""boundary\s*=\s*["']?([^"';\r\n]+)["']?""", RegexOption.IGNORE_CASE).find(fullContent)
        if (boundaryMatch != null) {
            boundary = boundaryMatch.groupValues[1].trim()
        }

        if (boundary == null) {
            val lineMatch = Regex("""^--([A-Za-z0-9_=\-\.\+]+)""", RegexOption.MULTILINE).find(fullContent)
            if (lineMatch != null) {
                boundary = lineMatch.groupValues[1].trim()
            }
        }

        if (boundary.isNullOrEmpty()) {
            val plain = LegacyDocSession.extractPrintableRunsFromBinary(stream)
            return MhtmlResult(
                htmlContent = fullContent,
                plainText = plain,
                embeddedImages = emptyList()
            )
        }

        val parts = fullContent.split("--$boundary")
        var primaryHtml: String? = null
        var fallbackHtml: String? = null
        val resourceMap = HashMap<String, String>()
        val imagesList = mutableListOf<Bitmap>()

        for (part in parts) {
            val trimmedPart = part.trim()
            if (trimmedPart.isEmpty() || trimmedPart == "--") continue

            val headerEnd = trimmedPart.indexOf("\r\n\r\n").let {
                if (it >= 0) it else trimmedPart.indexOf("\n\n")
            }
            if (headerEnd < 0) continue

            val rawHeaderText = trimmedPart.substring(0, headerEnd)
            val bodyText = trimmedPart.substring(headerEnd).trimStart('\r', '\n')

            val unfoldedHeaders = rawHeaderText.replace(Regex("""\r?\n[ \t]+"""), " ")

            var contentType = ""
            var encoding = ""
            var location: String? = null
            var contentId: String? = null
            var charsetName = "utf-8"

            for (hLine in unfoldedHeaders.split(Regex("""\r?\n"""))) {
                val colon = hLine.indexOf(':')
                if (colon < 0) continue
                val name = hLine.substring(0, colon).trim().lowercase(Locale.ROOT)
                val valStr = hLine.substring(colon + 1).trim()

                when (name) {
                    "content-type" -> {
                        contentType = valStr.substringBefore(';').trim().lowercase(Locale.ROOT)
                        val csMatch = Regex("""charset\s*=\s*["']?([^"';\r\n]+)["']?""", RegexOption.IGNORE_CASE).find(valStr)
                        if (csMatch != null) charsetName = csMatch.groupValues[1].trim()
                    }
                    "content-transfer-encoding" -> encoding = valStr.lowercase(Locale.ROOT)
                    "content-location" -> location = valStr
                    "content-id" -> contentId = valStr.trim('<', '>', ' ')
                }
            }

            val decodedBytes: ByteArray = when (encoding) {
                "base64" -> {
                    try {
                        val cleanB64 = bodyText.replace(Regex("""\s+"""), "")
                        android.util.Base64.decode(cleanB64, android.util.Base64.DEFAULT)
                    } catch (_: Throwable) {
                        bodyText.toByteArray(StandardCharsets.UTF_8)
                    }
                }
                "quoted-printable" -> decodeQuotedPrintable(bodyText)
                else -> {
                    val resolvedCs = try { Charset.forName(charsetName) } catch (_: Throwable) { StandardCharsets.UTF_8 }
                    bodyText.toByteArray(resolvedCs)
                }
            }

            val resolvedCharset = try { Charset.forName(charsetName) } catch (_: Throwable) { StandardCharsets.UTF_8 }
            val decodedStr = String(decodedBytes, resolvedCharset)

            if (contentType.contains("text/html") || contentType.contains("application/xhtml") || decodedStr.contains("<html", true) || decodedStr.contains("<!DOCTYPE", true)) {
                if (primaryHtml == null) {
                    primaryHtml = decodedStr
                } else if (fallbackHtml == null) {
                    fallbackHtml = decodedStr
                }
            } else if (contentType.startsWith("image/")) {
                val mime = if (contentType.isNotEmpty()) contentType else "image/png"
                val b64Data = android.util.Base64.encodeToString(decodedBytes, android.util.Base64.NO_WRAP)
                val dataUri = "data:$mime;base64,$b64Data"
                location?.let {
                    resourceMap[it] = dataUri
                    val lastSeg = it.substringAfterLast('/')
                    if (lastSeg.isNotEmpty()) resourceMap[lastSeg] = dataUri
                }
                contentId?.let {
                    resourceMap["cid:$it"] = dataUri
                    resourceMap[it] = dataUri
                }

                EmbeddedImageScanner.decodeMetafileOrSvgBitmap(decodedBytes, 0, decodedBytes.size)?.let { bmp ->
                    if (imagesList.size < 32) imagesList.add(bmp)
                }
            } else if (contentType.contains("text/css")) {
                val b64Data = android.util.Base64.encodeToString(decodedBytes, android.util.Base64.NO_WRAP)
                val dataUri = "data:text/css;base64,$b64Data"
                location?.let { resourceMap[it] = dataUri }
                contentId?.let { resourceMap["cid:$it"] = dataUri }
            }
        }

        var finalHtml = primaryHtml ?: fallbackHtml ?: fullContent
        for ((resKey, dataUri) in resourceMap) {
            finalHtml = finalHtml.replace(resKey, dataUri)
        }

        val plainText = stripMobiHtmlToRichText(finalHtml, imagesList.size)
        return MhtmlResult(
            htmlContent = finalHtml,
            plainText = plainText,
            embeddedImages = imagesList
        )
    }

    private fun decodeQuotedPrintable(input: String): ByteArray {
        val bos = ByteArrayOutputStream(input.length)
        var i = 0
        val len = input.length
        val bytes = input.toByteArray(StandardCharsets.ISO_8859_1)
        while (i < len) {
            val c = bytes[i].toInt() and 0xFF
            if (c == '='.code) {
                if (i + 2 < len) {
                    val h1 = (bytes[i + 1].toInt() and 0xFF).toChar()
                    val h2 = (bytes[i + 2].toInt() and 0xFF).toChar()
                    if (h1 == '\r' && h2 == '\n') {
                        i += 3
                        continue
                    } else if (h1 == '\n') {
                        i += 2
                        continue
                    }
                    val hex = "$h1$h2"
                    val bVal = hex.toIntOrNull(16)
                    if (bVal != null) {
                        bos.write(bVal)
                        i += 3
                        continue
                    }
                }
            }
            bos.write(c)
            i++
        }
        return bos.toByteArray()
    }
}
