/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

object PdfPermissionParser {
    const val PDF_DRM_ERROR =
        "DRM_PROTECTED: This PDF document is protected by cryptographic DRM. Circumventing DRM protection is prohibited under DMCA § 1201 and international copyright law."

    private val ENCRYPT_REF_REGEX = Regex("""/Encrypt\s+(\d+)\s+(\d+)\s+R""")
    private val PERM_P_REGEX = Regex("""/P\s+(-?\d+)""")
    private val FILTER_REGEX = Regex("""/Filter\s*/([A-Za-z0-9._+#-]+)""")
    private val HEX_NAME_ESCAPE_REGEX = Regex("""#([0-9A-Fa-f]{2})""")
    private val DRM_SECURITY_FILTERS = setOf(
        "ebx_handler",
        "adobe.aps",
        "adobe.pubsec",
        "pubsec",
        "fopn_foweb",
        "fileopen",
        "seclore",
        "microsoft.rms",
        "vitrium",
        "locklizard",
        "drmx"
    )

    private fun normalizePdfName(raw: String): String {
        return HEX_NAME_ESCAPE_REGEX.replace(raw) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }.lowercase(Locale.ROOT)
    }

    private fun isDrmSecurityFilterName(rawFilter: String): Boolean {
        val norm = normalizePdfName(rawFilter)
        return norm in DRM_SECURITY_FILTERS || norm != "standard"
    }

    fun isDrmProtected(bytes: ByteArray): Boolean {
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        if (!text.contains("/Encrypt")) return false
        val refMatch = ENCRYPT_REF_REGEX.findAll(text).lastOrNull()
        if (refMatch != null) {
            val objNum = refMatch.groupValues[1]
            val genNum = refMatch.groupValues[2]
            val objRegex = Regex("""(?<![0-9])$objNum\s+$genNum\s+obj\b(.*?)endobj""", RegexOption.DOT_MATCHES_ALL)
            return parseDrmFilterFromObjectMatches(objRegex, text) == true
        }
        return checkInlineDrmFilter(text) == true
    }

    fun isDrmProtected(source: ZeroCacheReadableSource): Boolean =
        isDrmProtected(source.size) { offset, buf -> source.readAt(offset, buf) }

    internal fun isDrmProtected(size: Long, readAt: (Long, ByteBuffer) -> Int): Boolean {
        return try {
            if (size <= 0L) return false
            val tailLen = minOf(size, 64 * 1024L).toInt()
            val tailText = readAsciiChunk(maxOf(0L, size - tailLen), tailLen, readAt)
            val headText = if (size > tailLen) readAsciiChunk(0L, minOf(size, 64 * 1024L).toInt(), readAt) else tailText
            val probeCombined = if (headText === tailText) tailText else headText + "\n" + tailText
            if (!probeCombined.contains("/Encrypt")) return false

            val refMatch = ENCRYPT_REF_REGEX.findAll(tailText).lastOrNull()
                ?: ENCRYPT_REF_REGEX.findAll(probeCombined).lastOrNull()
            if (refMatch == null) {
                return checkInlineDrmFilter(tailText) ?: checkInlineDrmFilter(probeCombined) ?: false
            }

            val objNum = refMatch.groupValues[1]
            val genNum = refMatch.groupValues[2]
            val objRegex = Regex("""(?<![0-9])$objNum\s+$genNum\s+obj\b(.*?)endobj""", RegexOption.DOT_MATCHES_ALL)
            if (size <= tailLen.toLong()) {
                return parseDrmFilterFromObjectMatches(objRegex, tailText) ?: false
            }

            val windowSize = 256 * 1024
            val overlap = 4096
            val step = (windowSize - overlap).toLong()
            val directBuf = ByteBuffer.allocateDirect(windowSize)
            val chunkArr = ByteArray(windowSize)
            var lastResult: Boolean? = null
            var pos = 0L
            while (pos < size) {
                val toRead = minOf(windowSize.toLong(), size - pos).toInt()
                directBuf.clear()
                directBuf.limit(toRead)
                val r = readAt(pos, directBuf)
                if (r <= 0) break
                directBuf.flip()
                directBuf.get(chunkArr, 0, r)
                val chunkText = String(chunkArr, 0, r, StandardCharsets.ISO_8859_1)
                parseDrmFilterFromObjectMatches(objRegex, chunkText)?.let { lastResult = it }
                if (pos + r >= size) break
                pos += step
            }
            lastResult ?: parseDrmFilterFromObjectMatches(objRegex, probeCombined) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun readAsciiChunk(offset: Long, len: Int, readAt: (Long, ByteBuffer) -> Int): String {
        val buf = ByteBuffer.allocateDirect(len)
        val r = readAt(offset, buf)
        if (r <= 0) return ""
        val arr = ByteArray(r)
        buf.flip()
        buf.get(arr)
        return String(arr, StandardCharsets.ISO_8859_1)
    }

    private fun parseDrmFilterFromObjectMatches(objRegex: Regex, text: String): Boolean? {
        var result: Boolean? = null
        for (m in objRegex.findAll(text)) {
            val filter = FILTER_REGEX.find(m.groupValues[1])?.groupValues?.get(1)
            if (filter != null) {
                result = isDrmSecurityFilterName(filter)
            }
        }
        return result
    }

    private fun checkInlineDrmFilter(text: String): Boolean? {
        val encIdx = text.lastIndexOf("/Encrypt")
        if (encIdx >= 0) {
            val slice = text.substring(encIdx, minOf(text.length, encIdx + 1024))
            val filter = FILTER_REGEX.find(slice)?.groupValues?.get(1)
            if (filter != null) return isDrmSecurityFilterName(filter)
        }
        return null
    }

    fun isCopyRestricted(bytes: ByteArray): Boolean {
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        return isCopyRestrictedInText(text, text)
    }

    fun isCopyRestricted(source: ZeroCacheReadableSource): Boolean =
        isCopyRestricted(source.size) { offset, buf -> source.readAt(offset, buf) }

    internal fun isCopyRestricted(size: Long, readAt: (Long, ByteBuffer) -> Int): Boolean {
        return try {
            if (size <= 0L) return false
            val tailLen = minOf(size, 64 * 1024L).toInt()
            val tailBuf = ByteBuffer.allocateDirect(tailLen)
            val tr = readAt(maxOf(0L, size - tailLen), tailBuf)
            val tailText = if (tr > 0) {
                val arr = ByteArray(tr)
                tailBuf.flip()
                tailBuf.get(arr)
                String(arr, StandardCharsets.ISO_8859_1)
            } else ""

            val headLen = minOf(size, 64 * 1024L).toInt()
            val headText = if (size > tailLen) {
                val headBuf = ByteBuffer.allocateDirect(headLen)
                val hr = readAt(0L, headBuf)
                if (hr > 0) {
                    val arr = ByteArray(hr)
                    headBuf.flip()
                    headBuf.get(arr)
                    String(arr, StandardCharsets.ISO_8859_1)
                } else ""
            } else tailText

            val probeCombined = if (headText === tailText) tailText else headText + "\n" + tailText
            if (!probeCombined.contains("/Encrypt")) return false

            val refMatch = ENCRYPT_REF_REGEX.findAll(tailText).lastOrNull()
                ?: ENCRYPT_REF_REGEX.findAll(probeCombined).lastOrNull()
            if (refMatch == null) {
                return checkInlineEncryptPermissions(tailText) ?: checkInlineEncryptPermissions(probeCombined) ?: false
            }

            val objNum = refMatch.groupValues[1]
            val genNum = refMatch.groupValues[2]
            val objRegex = Regex("""(?<![0-9])$objNum\s+$genNum\s+obj\b(.*?)endobj""", RegexOption.DOT_MATCHES_ALL)

            if (size <= tailLen.toLong()) {
                return parsePermissionFromObjectMatches(objRegex, tailText) ?: false
            }

            val windowSize = 256 * 1024
            val overlap = 4096
            val step = (windowSize - overlap).toLong()
            val directBuf = ByteBuffer.allocateDirect(windowSize)
            val chunkArr = ByteArray(windowSize)
            var lastResult: Boolean? = null
            var pos = 0L
            while (pos < size) {
                val toRead = minOf(windowSize.toLong(), size - pos).toInt()
                directBuf.clear()
                directBuf.limit(toRead)
                val r = readAt(pos, directBuf)
                if (r <= 0) break
                directBuf.flip()
                directBuf.get(chunkArr, 0, r)
                val chunkText = String(chunkArr, 0, r, StandardCharsets.ISO_8859_1)
                parsePermissionFromObjectMatches(objRegex, chunkText)?.let { lastResult = it }
                if (pos + r >= size) break
                pos += step
            }
            lastResult ?: parsePermissionFromObjectMatches(objRegex, probeCombined) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isCopyRestrictedInText(trailerText: String, fullText: String): Boolean {
        if (!trailerText.contains("/Encrypt") && !fullText.contains("/Encrypt")) return false
        return checkEncryptPermissions(trailerText, fullText) == true
    }

    private fun parsePermissionFromObjectMatches(objRegex: Regex, text: String): Boolean? {
        var result: Boolean? = null
        for (m in objRegex.findAll(text)) {
            val pVal = PERM_P_REGEX.findAll(m.groupValues[1]).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()?.toInt()
            if (pVal != null) {
                result = (pVal and 16) == 0
            }
        }
        return result
    }

    private fun checkInlineEncryptPermissions(text: String): Boolean? {
        val encIdx = text.lastIndexOf("/Encrypt")
        if (encIdx >= 0) {
            val slice = text.substring(encIdx, minOf(text.length, encIdx + 1024))
            val pVal = PERM_P_REGEX.findAll(slice).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()?.toInt()
            if (pVal != null) return (pVal and 16) == 0
        }
        return null
    }

    private fun checkEncryptPermissions(trailerText: String, searchCorpus: String): Boolean? {
        val refMatch = ENCRYPT_REF_REGEX.findAll(trailerText).lastOrNull()
            ?: ENCRYPT_REF_REGEX.findAll(searchCorpus).lastOrNull()
        if (refMatch != null) {
            val objNum = refMatch.groupValues[1]
            val genNum = refMatch.groupValues[2]
            val objRegex = Regex("""(?<![0-9])$objNum\s+$genNum\s+obj\b(.*?)endobj""", RegexOption.DOT_MATCHES_ALL)
            return parsePermissionFromObjectMatches(objRegex, searchCorpus)
        }
        return checkInlineEncryptPermissions(trailerText)
    }
}

object EpubDrmDetector {
    const val EPUB_DRM_ERROR =
        "DRM_PROTECTED: This EPUB e-book is protected by cryptographic DRM. Circumventing DRM protection is prohibited under DMCA § 1201 and international copyright law."

    private val ALLOWED_FONT_OBFUSCATION = setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#rc"
    )
    private val ENCRYPTED_DATA_REGEX = Regex("""<(?:\w+:)?EncryptedData\b(.*?)</(?:\w+:)?EncryptedData>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ALGORITHM_REGEX = Regex("""<(?:\w+:)?EncryptionMethod\b[^>]*\bAlgorithm\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CIPHER_URI_REGEX = Regex("""<(?:\w+:)?CipherReference\b[^>]*\bURI\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2", "ttc")

    fun isDrmProtected(entries: Map<String, ByteArray>): Boolean {
        for (key in entries.keys) {
            val norm = key.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
            if (norm == "meta-inf/rights.xml" || norm == "meta-inf/license.lcpl" || norm == "meta-inf/sinf.xml") {
                return true
            }
        }
        val encKey = entries.keys.firstOrNull {
            it.replace('\\', '/').trimStart('/').equals("meta-inf/encryption.xml", ignoreCase = true)
        } ?: return false
        val encBytes = entries[encKey] ?: return false
        val xml = EbookTextSession.decodeAndPreprocessXml(encBytes)
        val blocks = ENCRYPTED_DATA_REGEX.findAll(xml).toList()
        if (blocks.isEmpty()) {
            return xml.contains("EncryptedData", ignoreCase = true)
        }
        for (block in blocks) {
            val body = block.groupValues[1]
            val alg = ALGORITHM_REGEX.find(body)?.groupValues?.get(1)?.trim()?.lowercase(Locale.ROOT)
            val uri = CIPHER_URI_REGEX.find(body)?.groupValues?.get(1)?.trim()?.lowercase(Locale.ROOT) ?: ""
            val ext = uri.substringAfterLast('.', "")
            val isAllowedFontObfuscation = alg in ALLOWED_FONT_OBFUSCATION &&
                    uri.isNotEmpty() &&
                    (ext in FONT_EXTENSIONS || uri.contains("font"))
            if (!isAllowedFontObfuscation) {
                return true
            }
        }
        return false
    }
}
