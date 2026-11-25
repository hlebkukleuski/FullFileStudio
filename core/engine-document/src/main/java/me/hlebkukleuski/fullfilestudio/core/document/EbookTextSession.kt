/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.system.Os
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import me.hlebkukleuski.fullfilestudio.core.engine.DetectedFormat
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import org.xmlpull.v1.XmlPullParser

class EbookTextSession(
    private val pfd: ParcelFileDescriptor,
    private val format: DetectedFormat,
    private val extension: String,
    private val password: String? = null
) : DocumentSession {
    @Volatile private var isClosed = false
    private val pages = mutableListOf<String>()
    private val embeddedImages = mutableListOf<Bitmap>()
    private var fullText = ""
    private var parseError: String? = null

    private val textPaint = TextPaint().apply {
        color = Color.rgb(30, 32, 38)
        textSize = 24f
        typeface = Typeface.SERIF
        isAntiAlias = true
    }

    init {
        try {
            val fileSize = resolveStreamDescriptorSize(pfd.fileDescriptor)
            val isZipContainer = format == DetectedFormat.EPUB ||
                    format == DetectedFormat.DOCX ||
                    format == DetectedFormat.ODT ||
                    extension == "fbz" ||
                    extension.endsWith(".zip")

            if (isZipContainer) {
                val hdr = ByteBuffer.allocateDirect(8)
                val r = try { Os.pread(pfd.fileDescriptor, hdr, 0L) } catch (_: Throwable) { 0 }
                if (r >= 4 &&
                    (hdr.get(0).toInt() and 0xFF) == 0xD0 &&
                    (hdr.get(1).toInt() and 0xFF) == 0xCF &&
                    (hdr.get(2).toInt() and 0xFF) == 0x11 &&
                    (hdr.get(3).toInt() and 0xFF) == 0xE0
                ) {
                    val legacyFormat = when (format) {
                        DetectedFormat.DOCX -> DetectedFormat.DOC
                        DetectedFormat.XLSX -> DetectedFormat.XLS
                        DetectedFormat.PPTX -> DetectedFormat.PPT
                        else -> DetectedFormat.DOC
                    }
                    val legacySession = LegacyAndIsoEngine.createSession(pfd.fileDescriptor, legacyFormat, extension, password)
                    try {
                        parseError = legacySession.getLastError()
                        fullText = legacySession.getPageText(-1)
                    } finally {
                        legacySession.close()
                    }
                } else {
                    val rawEntries = extractSingleZipArchive(pfd.fileDescriptor, fileSize)
                    if (EpubDrmDetector.isDrmProtected(rawEntries)) {
                        parseError = EpubDrmDetector.EPUB_DRM_ERROR
                    } else {
                        val manifestKey = rawEntries.keys.firstOrNull { it.equals("META-INF/manifest.xml", ignoreCase = true) }
                        val entries = if (manifestKey != null && OfficeCrypto.isEncryptedOdfManifest(rawEntries[manifestKey])) {
                            if (password.isNullOrEmpty()) {
                                parseError = "PASSWORD_REQUIRED: ODF document is encrypted or password protected."
                                emptyMap()
                            } else {
                                OfficeCrypto.decryptOdfEntries(rawEntries, password) ?: run {
                                    parseError = "PASSWORD_REQUIRED: Incorrect password for ODF document."
                                    emptyMap()
                                }
                            }
                        } else {
                            rawEntries
                        }
                        if (parseError == null) {
                            fullText = parseZipTextMap(entries)
                        }
                    }
                }
            } else {
                val maxRead = if (fileSize > 0L) minOf(fileSize, 32 * 1024 * 1024L).toInt() else 16 * 1024 * 1024
                val buf = ByteBuffer.allocateDirect(maxRead)
                var total = 0
                while (buf.hasRemaining()) {
                    val r = Os.pread(pfd.fileDescriptor, buf, total.toLong())
                    if (r <= 0) break
                    total += r
                }
                buf.flip()
                val bytes = ByteArray(total)
                buf.get(bytes)

                if (format == DetectedFormat.MHTML || extension in setOf("mhtml", "mht")) {
                    val result = MhtmlParser.parse(bytes)
                    fullText = result.htmlContent
                    embeddedImages.addAll(result.embeddedImages)
                } else if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                    val rawEntries = extractSingleZipArchive(pfd.fileDescriptor, fileSize)
                    if (EpubDrmDetector.isDrmProtected(rawEntries)) {
                        parseError = EpubDrmDetector.EPUB_DRM_ERROR
                    } else {
                        val manifestKey = rawEntries.keys.firstOrNull { it.equals("META-INF/manifest.xml", ignoreCase = true) }
                        val entries = if (manifestKey != null && OfficeCrypto.isEncryptedOdfManifest(rawEntries[manifestKey])) {
                            if (password.isNullOrEmpty()) {
                                parseError = "PASSWORD_REQUIRED: ODF document is encrypted or password protected."
                                emptyMap()
                            } else {
                                OfficeCrypto.decryptOdfEntries(rawEntries, password) ?: run {
                                    parseError = "PASSWORD_REQUIRED: Incorrect password for ODF document."
                                    emptyMap()
                                }
                            }
                        } else {
                            rawEntries
                        }
                        if (parseError == null) {
                            fullText = parseZipTextMap(entries)
                        }
                    }
                } else {
                    val sb = StringBuilder()
                    extractXmlBytesWithCharset(
                        bytes = bytes,
                        out = sb,
                        onBinaryImageDecoded = { imgBytes ->
                            if (embeddedImages.size < 32) {
                                EmbeddedImageScanner.decodeMetafileOrSvgBitmap(imgBytes, 0, imgBytes.size)?.let { bmp ->
                                    val idx = embeddedImages.size
                                    embeddedImages.add(bmp)
                                    sb.append("\n[IMAGE:$idx]\n")
                                }
                            }
                        }
                    )
                    fullText = sb.toString()
                }
            }
        } catch (sec: SecurityException) {
            parseError = sec.message ?: "Заблокирована потенциальная XXE/Zip-Bomb атака."
        } catch (e: Exception) {
            parseError = "Ошибка структуры контейнера (${format.name}): ${e.localizedMessage ?: "файл повреждён"}"
        }

        if (fullText.isBlank() && embeddedImages.isEmpty() && parseError == null) {
            parseError = "Не удалось извлечь текст из контейнера ${format.name}: файл пуст, зашифрован или повреждён."
        }

        val isHtmlContainer = format == DetectedFormat.MHTML ||
                extension in setOf("mhtml", "mht", "html", "htm", "xhtml") ||
                fullText.trimStart().startsWith("<html", ignoreCase = true) ||
                fullText.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)

        if (isHtmlContainer && fullText.isNotBlank()) {
            pages.clear()
            pages.add(fullText)
        } else {
            paginateRichText(fullText, embeddedImages.size, pages)
        }
    }

    fun getEmbeddedImagesCount(): Int = embeddedImages.size
    fun getEmbeddedImage(index: Int): Bitmap? = embeddedImages.getOrNull(index)

    private fun extractSingleZipArchive(fd: FileDescriptor, fileSize: Long): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        val maxAllowedBytes = 96 * 1024 * 1024L

        if (fileSize >= 22L && tryReadZipCentralDirectory(fd, fileSize, maxAllowedBytes, result)) {
            if (result.isNotEmpty()) return result
        }

        extractZipStreamFallback(fd, fileSize, maxAllowedBytes, result)
        return result
    }

    private fun isTextOrXmlEntryName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".xml") ||
                lower.endsWith(".rels") ||
                lower.endsWith(".xhtml") ||
                lower.endsWith(".html") ||
                lower.endsWith(".htm") ||
                lower.endsWith(".opf") ||
                lower.endsWith(".ncx") ||
                lower.endsWith(".fb2") ||
                lower.endsWith(".txt")
    }

    private fun isSupportedImageEntry(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".png") ||
                lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") ||
                lower.endsWith(".gif") ||
                lower.endsWith(".bmp") ||
                lower.endsWith(".wmf") ||
                lower.endsWith(".emf") ||
                lower.endsWith(".svg") ||
                lower.endsWith(".tif") ||
                lower.endsWith(".tiff")
    }

    private fun tryReadZipCentralDirectory(
        fd: FileDescriptor,
        fileSize: Long,
        maxAllowedBytes: Long,
        outMap: MutableMap<String, ByteArray>
    ): Boolean {
        return try {
            val tailSize = minOf(fileSize, 65557L).toInt()
            val tailOffset = fileSize - tailSize
            val tailBuf = ByteBuffer.allocateDirect(tailSize).order(ByteOrder.LITTLE_ENDIAN)
            val readTail = preadFully(fd, tailBuf, tailOffset)
            if (readTail < 22) return false
            tailBuf.flip()

            var eocdPos = -1
            for (i in readTail - 22 downTo 0) {
                if (tailBuf.getInt(i) == 0x06054b50) {
                    eocdPos = i
                    break
                }
            }
            if (eocdPos < 0) return false

            val cdSize = tailBuf.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = tailBuf.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL
            if (cdOffset < 0L || cdSize <= 0L || cdOffset + cdSize > fileSize || cdSize > 32 * 1024 * 1024L) {
                return false
            }

            val cdBuf = ByteBuffer.allocateDirect(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            val cdRead = preadFully(fd, cdBuf, cdOffset)
            if (cdRead < cdSize.toInt()) return false
            cdBuf.flip()

            data class CdEntryCandidate(
                val rawName: String,
                val method: Int,
                val compSize: Long,
                val uncompSize: Long,
                val localHeaderOffset: Long,
                val isTextOrXml: Boolean
            )

            val candidates = ArrayList<CdEntryCandidate>()
            var pos = 0

            while (pos + 46 <= cdRead) {
                val sig = cdBuf.getInt(pos)
                if (sig != 0x02014b50) break

                val gpFlags = cdBuf.getShort(pos + 8).toInt() and 0xFFFF
                val method = cdBuf.getShort(pos + 10).toInt() and 0xFFFF
                val compSize = cdBuf.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                val uncompSize = cdBuf.getInt(pos + 24).toLong() and 0xFFFFFFFFL
                val nameLen = cdBuf.getShort(pos + 28).toInt() and 0xFFFF
                val extraLen = cdBuf.getShort(pos + 30).toInt() and 0xFFFF
                val commentLen = cdBuf.getShort(pos + 32).toInt() and 0xFFFF
                val localHeaderOffset = cdBuf.getInt(pos + 42).toLong() and 0xFFFFFFFFL

                val nextPos = pos + 46 + nameLen + extraLen + commentLen
                if (pos + 46 + nameLen > cdRead) break

                if ((gpFlags and 0x01) != 0) {
                    throw SecurityException("UNSUPPORTED_ENCRYPTION: ZIP-container password encryption is not supported.")
                }

                val nameBytes = ByteArray(nameLen)
                cdBuf.position(pos + 46)
                cdBuf.get(nameBytes)
                val rawName = String(nameBytes, StandardCharsets.UTF_8)

                if (!rawName.endsWith("/")) {
                    if (uncompSize != 0xFFFFFFFFL && uncompSize > 16L * 1024L * 1024L &&
                        compSize > 0L && (uncompSize / compSize) > 150L
                    ) {
                        throw SecurityException("Превышен безопасный лимит распаковки контейнера (защита от Zip-бомб).")
                    }
                    candidates.add(
                        CdEntryCandidate(
                            rawName = rawName,
                            method = method,
                            compSize = compSize,
                            uncompSize = uncompSize,
                            localHeaderOffset = localHeaderOffset,
                            isTextOrXml = isTextOrXmlEntryName(rawName)
                        )
                    )
                }
                pos = nextPos
            }

            val prioritized = candidates.sortedByDescending { it.isTextOrXml }
            var totalUnpacked = 0L
            val localHeaderBuf = ByteBuffer.allocateDirect(30).order(ByteOrder.LITTLE_ENDIAN)

            for (cand in prioritized) {
                val maxSingleEntry = if (cand.isTextOrXml) maxAllowedBytes else 32 * 1024 * 1024L
                if (cand.compSize !in 0L..maxSingleEntry || cand.localHeaderOffset + 30L > fileSize) continue
                if (!cand.isTextOrXml && totalUnpacked >= maxAllowedBytes) continue
                if (cand.uncompSize != 0xFFFFFFFFL && totalUnpacked + cand.uncompSize > maxAllowedBytes + 32 * 1024 * 1024L) {
                    if (!cand.isTextOrXml) continue
                }

                localHeaderBuf.clear()
                if (preadFully(fd, localHeaderBuf, cand.localHeaderOffset) == 30 &&
                    localHeaderBuf.getInt(0) == 0x04034b50
                ) {
                    val lNameLen = localHeaderBuf.getShort(26).toInt() and 0xFFFF
                    val lExtraLen = localHeaderBuf.getShort(28).toInt() and 0xFFFF
                    val dataOffset = cand.localHeaderOffset + 30L + lNameLen + lExtraLen
                    if (dataOffset + cand.compSize <= fileSize) {
                        val compBuf = ByteBuffer.allocateDirect(cand.compSize.toInt())
                        val got = preadFully(fd, compBuf, dataOffset)
                        if (got == cand.compSize.toInt()) {
                            compBuf.flip()
                            val compArray = ByteArray(got)
                            compBuf.get(compArray)
                            val entryBudget = (maxAllowedBytes + 32 * 1024 * 1024L - totalUnpacked).coerceAtLeast(8 * 1024 * 1024L)
                            val entryData = when (cand.method) {
                                0 -> compArray
                                8 -> inflateRawEntry(compArray, entryBudget)
                                else -> null
                            }
                            if (entryData != null) {
                                totalUnpacked += entryData.size
                                outMap[cand.rawName.replace('\\', '/').trimStart('/')] = entryData
                            }
                        }
                    }
                }
            }
            outMap.isNotEmpty()
        } catch (sec: SecurityException) {
            throw sec
        } catch (_: Throwable) {
            false
        }
    }

    private fun preadFully(fd: FileDescriptor, buf: ByteBuffer, startOffset: Long): Int {
        var total = 0
        while (buf.hasRemaining()) {
            val r = try {
                Os.pread(fd, buf, startOffset + total)
            } catch (_: Throwable) {
                -1
            }
            if (r <= 0) break
            total += r
        }
        return total
    }

    private fun inflateRawEntry(compressed: ByteArray, maxAllowed: Long): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(compressed)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var total = 0L
            val compLen = compressed.size.coerceAtLeast(1).toLong()
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count > 0) {
                    total += count
                    if (total > 16L * 1024L * 1024L && (total / compLen) > 150L) {
                        throw SecurityException("Превышен безопасный лимит распаковки записи (защита от Zip-бомб).")
                    }
                    if (total > maxAllowed) {
                        return null
                    }
                    bos.write(buf, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            bos.toByteArray()
        } catch (sec: SecurityException) {
            throw sec
        } catch (_: Throwable) {
            null
        } finally {
            try { inflater.end() } catch (_: Throwable) {}
        }
    }

    private fun extractZipStreamFallback(
        fd: FileDescriptor,
        fileSize: Long,
        maxAllowedBytes: Long,
        outMap: MutableMap<String, ByteArray>
    ) {
        val safeStream = BufferedInputStream(PositionalPreadInputStream(fd, fileSize), 32 * 1024)
        ZipInputStream(safeStream).use { zis ->
            var totalBytes = 0L
            val buffer = ByteArray(16384)
            while (true) {
                val entry = try {
                    zis.nextEntry
                } catch (_: Throwable) {
                    break
                } ?: break

                if (!entry.isDirectory) {
                    val normName = entry.name.replace('\\', '/').trimStart('/')
                    val isText = isTextOrXmlEntryName(normName)
                    if (!isText && totalBytes >= maxAllowedBytes) {
                        try { zis.closeEntry() } catch (_: Throwable) {}
                        continue
                    }
                    try {
                        val bos = ByteArrayOutputStream()
                        var entryUnpacked = 0L
                        val entryCompHint = entry.compressedSize.takeIf { it > 0L } ?: fileSize.coerceAtLeast(1L)
                        var read: Int
                        while (zis.read(buffer).also { read = it } > 0) {
                            entryUnpacked += read
                            totalBytes += read
                            if (entryUnpacked > 16L * 1024L * 1024L &&
                                (entryUnpacked / entryCompHint.coerceAtLeast(1L)) > 150L
                            ) {
                                throw SecurityException("Превышен безопасный лимит распаковки контейнера (защита от Zip-бомб).")
                            }
                            if (totalBytes > maxAllowedBytes + 32 * 1024 * 1024L) {
                                if (fileSize in 1L..(totalBytes / 150L)) {
                                    throw SecurityException("Превышен безопасный лимит распаковки контейнера (защита от Zip-бомб).")
                                }
                                break
                            }
                            bos.write(buffer, 0, read)
                        }
                        if (bos.size() > 0) {
                            outMap[normName] = bos.toByteArray()
                        }
                    } catch (sec: SecurityException) {
                        throw sec
                    } catch (_: Throwable) {
                    }
                }
                try { zis.closeEntry() } catch (_: Throwable) {}
            }
        }
    }

    private fun extractDocxRelationships(relsBytes: ByteArray?, baseDir: String): Map<String, String> {
        if (relsBytes == null) return emptyMap()
        val map = HashMap<String, String>()
        try {
            val parser = createSafeXmlParser()
            parser.setInput(InputStreamReader(ByteArrayInputStream(relsBytes), StandardCharsets.UTF_8))
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                val local = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                if (ev == XmlPullParser.START_TAG && local == "relationship") {
                    val id = parser.getAttributeValue(null, "Id") ?: parser.getAttributeValue(null, "id")
                    val target = parser.getAttributeValue(null, "Target") ?: parser.getAttributeValue(null, "target")
                    if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                        val clean = target.replace('\\', '/').trim()
                        val resolved = when {
                            clean.startsWith("/") -> clean.trimStart('/')
                            clean.startsWith("$baseDir/", ignoreCase = true) -> clean
                            baseDir.isEmpty() -> clean
                            else -> "$baseDir/$clean"
                        }
                        val parts = resolved.split('/')
                        val normParts = ArrayList<String>(parts.size)
                        for (p in parts) {
                            if (p.isEmpty() || p == ".") continue
                            if (p == "..") {
                                if (normParts.isNotEmpty()) normParts.removeAt(normParts.lastIndex)
                            } else {
                                normParts.add(p)
                            }
                        }
                        map[id] = normParts.joinToString("/")
                    }
                }
                ev = parser.next()
            }
        } catch (_: Throwable) {}
        return map
    }

    private fun parseZipTextMap(entries: Map<String, ByteArray>): String {
        val sb = StringBuilder()
        if (entries.isEmpty()) return ""

        val imagePathToIndex = HashMap<String, Int>()
        for ((name, data) in entries) {
            if (embeddedImages.size >= 32) break
            if (isSupportedImageEntry(name) && data.size in 32..(12 * 1024 * 1024)) {
                EmbeddedImageScanner.decodeMetafileOrSvgBitmap(data, 0, data.size)?.let { bmp ->
                    val idx = embeddedImages.size
                    embeddedImages.add(bmp)
                    val normKey = name.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
                    imagePathToIndex[normKey] = idx
                    imagePathToIndex[normKey.substringAfterLast('/')] = idx
                }
            }
        }

        val relIdToImageIndex = HashMap<String, Int>()
        val docxRelsBytes = entries["word/_rels/document.xml.rels"]
            ?: entries.entries.firstOrNull { it.key.endsWith("document.xml.rels", ignoreCase = true) }?.value
        if (docxRelsBytes != null) {
            val relsMap = extractDocxRelationships(docxRelsBytes, "word")
            for ((rId, targetPath) in relsMap) {
                val lowerTarget = targetPath.lowercase(Locale.ROOT)
                val imgIdx = imagePathToIndex[lowerTarget] ?: imagePathToIndex[lowerTarget.substringAfterLast('/')]
                if (imgIdx != null) {
                    relIdToImageIndex[rId] = imgIdx
                }
            }
        }

        val emittedImageIndices = HashSet<Int>()
        val onInlineImageRef: (String) -> Unit = { rawRef ->
            val cleanRef = rawRef.replace('\\', '/').trim()
            val imgIdx = relIdToImageIndex[cleanRef]
                ?: imagePathToIndex[cleanRef.lowercase(Locale.ROOT).trimStart('/')]
                ?: imagePathToIndex[cleanRef.substringAfterLast('/').lowercase(Locale.ROOT)]
            if (imgIdx != null && emittedImageIndices.add(imgIdx)) {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                sb.append("[IMAGE:$imgIdx]\n")
            }
        }

        when {
            format == DetectedFormat.DOCX -> {
                val docKey = entries.keys.firstOrNull { it.equals("word/document.xml", ignoreCase = true) }
                    ?: entries.keys.firstOrNull { it.endsWith("/document.xml", ignoreCase = true) }
                if (docKey != null) {
                    extractXmlBytesWithCharset(entries[docKey]!!, sb, onInlineImageRef = onInlineImageRef)
                }
                entries.keys
                    .filter {
                        val l = it.lowercase(Locale.ROOT)
                        (l.startsWith("word/header") ||
                                l.startsWith("word/footer") ||
                                l == "word/footnotes.xml" ||
                                l == "word/endnotes.xml" ||
                                l == "word/comments.xml" ||
                                l.startsWith("word/diagrams/data")) &&
                                l.endsWith(".xml")
                    }
                    .sorted()
                    .forEach { k -> extractXmlBytesWithCharset(entries[k]!!, sb, onInlineImageRef = onInlineImageRef) }
            }
            format == DetectedFormat.ODT -> {
                val odtKey = entries.keys.firstOrNull { it.equals("content.xml", ignoreCase = true) }
                if (odtKey != null) {
                    extractXmlBytesWithCharset(entries[odtKey]!!, sb, onInlineImageRef = onInlineImageRef)
                }
            }
            format == DetectedFormat.EPUB -> {
                val orderedChapters = mutableListOf<ByteArray>()
                val opfEntry = entries.entries.firstOrNull { it.key.endsWith(".opf", ignoreCase = true) }
                if (opfEntry != null) {
                    try {
                        val opfDir = opfEntry.key.substringBeforeLast('/', "")
                        val manifestHrefById = HashMap<String, String>()
                        val spineIdrefs = mutableListOf<String>()

                        val parser = createSafeXmlParser()
                        parser.setInput(InputStreamReader(ByteArrayInputStream(opfEntry.value), StandardCharsets.UTF_8))
                        var ev = parser.eventType
                        while (ev != XmlPullParser.END_DOCUMENT) {
                            if (ev == XmlPullParser.START_TAG) {
                                val tag = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: ""
                                if (tag == "item") {
                                    val id = parser.getAttributeValue(null, "id")
                                    val href = parser.getAttributeValue(null, "href")
                                    if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                                        val decodedHref = try {
                                            java.net.URLDecoder.decode(href, "UTF-8")
                                        } catch (_: Exception) {
                                            href
                                        }
                                        val fullPath = if (opfDir.isEmpty()) decodedHref else "$opfDir/$decodedHref"
                                        manifestHrefById[id] = fullPath.trimStart('/')
                                    }
                                } else if (tag == "itemref") {
                                    val idref = parser.getAttributeValue(null, "idref")
                                    if (!idref.isNullOrBlank()) spineIdrefs.add(idref)
                                }
                            }
                            ev = parser.next()
                        }

                        for (idref in spineIdrefs) {
                            val targetPath = manifestHrefById[idref] ?: continue
                            val matchedEntry = entries.entries.firstOrNull {
                                it.key.equals(targetPath, ignoreCase = true) ||
                                        it.key.endsWith("/$targetPath", ignoreCase = true)
                            }
                            if (matchedEntry != null) {
                                orderedChapters.add(matchedEntry.value)
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (orderedChapters.isNotEmpty()) {
                    for (chapterBytes in orderedChapters) {
                        extractXmlBytesWithCharset(chapterBytes, sb, onInlineImageRef = onInlineImageRef)
                    }
                } else {
                    entries.entries
                        .filter {
                            val lower = it.key.lowercase(Locale.ROOT)
                            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
                        }
                        .sortedBy { it.key.lowercase(Locale.ROOT) }
                        .forEach { (_, data) -> extractXmlBytesWithCharset(data, sb, onInlineImageRef = onInlineImageRef) }
                }
            }
            extension == "fbz" || entries.keys.any { it.endsWith(".fb2", ignoreCase = true) } -> {
                entries.entries
                    .filter { it.key.endsWith(".fb2", ignoreCase = true) || it.key.endsWith(".xml", ignoreCase = true) }
                    .forEach { (_, data) ->
                        extractXmlBytesWithCharset(
                            bytes = data,
                            out = sb,
                            onBinaryImageDecoded = { imgBytes ->
                                if (embeddedImages.size < 32) {
                                    EmbeddedImageScanner.decodeMetafileOrSvgBitmap(imgBytes, 0, imgBytes.size)?.let { bmp ->
                                        val idx = embeddedImages.size
                                        embeddedImages.add(bmp)
                                        sb.append("\n[IMAGE:$idx]\n")
                                    }
                                }
                            },
                            onInlineImageRef = onInlineImageRef
                        )
                    }
            }
        }

        if (sb.isBlank()) {
            for ((name, data) in entries) {
                val lower = name.lowercase(Locale.ROOT)
                val isMeta = lower == "mimetype" ||
                        lower.contains("meta-inf/") ||
                        lower.startsWith("_rels/") ||
                        lower.endsWith(".rels") ||
                        lower == "[content_types].xml" ||
                        lower.endsWith(".opf") ||
                        lower.endsWith(".ncx")
                if (!isMeta && (lower.endsWith(".xml") || lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".fb2") || lower.endsWith(".txt"))) {
                    extractXmlBytesWithCharset(data, sb, onInlineImageRef = onInlineImageRef)
                }
            }
        }

        for (imgIdx in 0 until embeddedImages.size) {
            if (imgIdx !in emittedImageIndices) {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                sb.append("[IMAGE:$imgIdx]\n")
            }
        }

        return sb.toString()
    }

    companion object {
        private val XML_ENCODING_REGEX = Regex("""encoding\s*=\s*["']([A-Za-z0-9._\-]+)["']""", RegexOption.IGNORE_CASE)

        fun detectXmlCharset(bytes: ByteArray): Charset {
            if (bytes.isEmpty()) return StandardCharsets.UTF_8
            when {
                bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF ->
                    return StandardCharsets.UTF_8
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE ->
                    return StandardCharsets.UTF_16LE
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF ->
                    return StandardCharsets.UTF_16BE
            }
            val headLen = minOf(bytes.size, 512)
            val asciiHead = String(bytes, 0, headLen, StandardCharsets.ISO_8859_1)
            val match = XML_ENCODING_REGEX.find(asciiHead)
            val encName = match?.groupValues?.getOrNull(1)?.trim()
            if (!encName.isNullOrEmpty()) {
                return try {
                    Charset.forName(encName)
                } catch (_: Exception) {
                    StandardCharsets.UTF_8
                }
            }
            return try {
                PlainTextSession.detectCharsetAndValidateText(bytes, minOf(bytes.size, 8192)).first
            } catch (_: Exception) {
                StandardCharsets.UTF_8
            }
        }

        fun decodeAndPreprocessXml(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val (bomCharset, bomSkip) = when {
                bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF && (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF ->
                    StandardCharsets.UTF_8 to 3
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE ->
                    StandardCharsets.UTF_16LE to 2
                bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF ->
                    StandardCharsets.UTF_16BE to 2
                else -> null to 0
            }

            val charset: Charset = bomCharset ?: detectXmlCharset(bytes)

            val rawXml = String(bytes, bomSkip, bytes.size - bomSkip, charset)
            val withoutDoctype = stripDoctypePreserveEntities(rawXml)
            return decodeCommonHtmlEntities(withoutDoctype)
        }

        private fun stripDoctypePreserveEntities(xml: String): String {
            val idx = xml.indexOf("<!DOCTYPE", ignoreCase = true)
            if (idx < 0) return xml
            val headSlice = xml.substring(idx, minOf(xml.length, idx + 8192))
            if (headSlice.contains("<!ENTITY", ignoreCase = true) ||
                headSlice.contains("SYSTEM", ignoreCase = true) ||
                headSlice.contains("PUBLIC", ignoreCase = true)
            ) {
                if (headSlice.contains("<!ENTITY", ignoreCase = true)) {
                    throw SecurityException("Обнаружена запрещённая внешняя сущность (XXE) в DOCTYPE.")
                }
            }
            val sb = StringBuilder(xml.length)
            sb.append(xml, 0, idx)
            var p = idx + 9
            var bracketDepth = 0
            while (p < xml.length) {
                val c = xml[p]
                if (c == '[') bracketDepth++
                else if (c == ']' && bracketDepth > 0) bracketDepth--
                else if (c == '>' && bracketDepth == 0) {
                    p++
                    break
                }
                p++
            }
            if (p < xml.length) sb.append(xml, p, xml.length)
            return sb.toString()
        }

        private fun decodeCommonHtmlEntities(xml: String): String {
            if (!xml.contains('&')) return xml
            return xml
                .replace("&nbsp;", "\u00A0")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”")
                .replace("&bdquo;", "„")
                .replace("&lsquo;", "‘")
                .replace("&rsquo;", "’")
                .replace("&hellip;", "…")
                .replace("&copy;", "©")
                .replace("&reg;", "®")
                .replace("&trade;", "™")
                .replace("&sect;", "§")
                .replace("&bull;", "•")
                .replace("&deg;", "°")
                .replace("&plusmn;", "±")
                .replace("&times;", "×")
                .replace("&divide;", "÷")
                .replace("&euro;", "€")
        }

        fun createSafeXmlParser(): XmlPullParser {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isValidating = false
            try { factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
            return factory.newPullParser()
        }

        fun extractXmlBytesWithCharset(
            bytes: ByteArray,
            out: StringBuilder,
            onBinaryImageDecoded: ((ByteArray) -> Unit)? = null,
            onInlineImageRef: ((String) -> Unit)? = null
        ) {
            if (bytes.isEmpty()) return
            val xmlString = decodeAndPreprocessXml(bytes)
            val parser = createSafeXmlParser()
            parser.setInput(StringReader(xmlString))
            extractTextFromParser(parser, out, onBinaryImageDecoded, onInlineImageRef)
        }

        fun extractTextFromParser(
            parser: XmlPullParser,
            out: StringBuilder,
            onBinaryImageDecoded: ((ByteArray) -> Unit)? = null,
            onInlineImageRef: ((String) -> Unit)? = null
        ) {
            val ignoredSubtreeTags = setOf(
                "head", "style", "script",
                "office-automatic-styles", "automatic-styles",
                "office-styles", "styles", "master-styles",
                "font-face-decls", "file-info", "document-info", "publish-info",
                "custom-info", "stylesheet", "latentstyles",
                "fallback", "del", "deltext", "instrtext"
            )
            val blockBreakTags = setOf(
                "p", "h", "h1", "h2", "h3", "h4", "h5", "h6",
                "div", "li", "tr", "table-row", "row",
                "title", "subtitle", "epigraph", "annotation", "section", "v", "stanza",
                "br", "line-break"
            )

            var ignoreDepth = 0
            var inBinaryTag = false
            val binaryBase64Builder = StringBuilder()

            var inRun = false
            var inRPr = false
            var rPrBold = false
            var rPrItalic = false
            var rPrUnderline = false
            var rPrStrike = false
            var rPrVertAlign = ""
            var inRunText = false
            val runTextBuilder = StringBuilder()

            var activeHeadingLevel = 0
            var inListItem = false
            var listIndentLevel = 0
            var paragraphStartLength = -1
            var inTableCell = false
            var firstCellInRow = true

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT && out.length < 8_000_000) {
                val rawName = parser.name ?: ""
                val localName = rawName.substringAfterLast(':').lowercase(Locale.ROOT)

                when (eventType) {
                    XmlPullParser.DOCDECL -> {
                        throw SecurityException("Обнаружено объявление DOCTYPE/DTD (защита от XXE-инъекций).")
                    }
                    XmlPullParser.ENTITY_REF -> {
                        val resolved = parser.text
                        if (resolved.isNullOrEmpty()) {
                            throw SecurityException("Попытка обращения к внешней сущности XML (XXE).")
                        }
                    }
                    XmlPullParser.START_TAG -> {
                        if (ignoreDepth > 0) {
                            ignoreDepth++
                        } else if (localName == "binary" || localName == "binarydata") {
                            inBinaryTag = true
                            binaryBase64Builder.setLength(0)
                        } else if (localName in ignoredSubtreeTags) {
                            ignoreDepth = 1
                        } else {
                            when (localName) {
                                "blip", "imagedata", "image", "img" -> {
                                    if (onInlineImageRef != null) {
                                        for (aIdx in 0 until parser.attributeCount) {
                                            val aName = parser.getAttributeName(aIdx)?.substringAfterLast(':')?.lowercase(Locale.ROOT) ?: continue
                                            if (aName in setOf("embed", "id", "href", "src")) {
                                                val aVal = parser.getAttributeValue(aIdx)?.trim()?.removePrefix("#")
                                                if (!aVal.isNullOrEmpty()) {
                                                    onInlineImageRef(aVal)
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                                "h1", "title" -> {
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    out.append("<h1>")
                                    activeHeadingLevel = 1
                                }
                                "h2", "h3", "subtitle" -> {
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    out.append("<h2>")
                                    activeHeadingLevel = 2
                                }
                                "h" -> {
                                    val lvl = parser.getAttributeValue(null, "outline-level")?.toIntOrNull() ?: 1
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    if (lvl <= 1) {
                                        out.append("<h1>")
                                        activeHeadingLevel = 1
                                    } else {
                                        out.append("<h2>")
                                        activeHeadingLevel = 2
                                    }
                                }
                                "li", "list-item" -> {
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    out.append("• ")
                                    inListItem = true
                                }
                                "p" -> {
                                    paragraphStartLength = out.length
                                    val pStyle = parser.getAttributeValue(null, "style-name")?.lowercase(Locale.ROOT) ?: ""
                                    if (pStyle.contains("heading") || pStyle.contains("title")) {
                                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                        out.append("<h2>")
                                        activeHeadingLevel = 2
                                    }
                                    listIndentLevel = 0
                                }
                                "pstyle" -> {
                                    val valAttr = parser.getAttributeValue(null, "val")?.lowercase(Locale.ROOT) ?: ""
                                    if ((valAttr.contains("heading1") || valAttr == "title") && activeHeadingLevel == 0) {
                                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                        out.append("<h1>")
                                        activeHeadingLevel = 1
                                    } else if ((valAttr.contains("heading") || valAttr.contains("subtitle")) && activeHeadingLevel == 0) {
                                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                        out.append("<h2>")
                                        activeHeadingLevel = 2
                                    }
                                }
                                "ilvl" -> {
                                    listIndentLevel = (parser.getAttributeValue(null, "val")?.toIntOrNull() ?: 0).coerceIn(0, 6)
                                }
                                "numpr" -> {
                                    if (!inListItem && !inTableCell) {
                                        if (out.isNotEmpty() && !out.endsWith("\n") && !out.endsWith("• ") && !out.endsWith("◦ ")) {
                                            out.append('\n')
                                        }
                                        if (!out.endsWith("• ") && !out.endsWith("◦ ")) {
                                            repeat(listIndentLevel) { out.append("  ") }
                                            out.append(if (listIndentLevel > 0) "◦ " else "• ")
                                        }
                                        inListItem = true
                                    }
                                }
                                "tr", "table-row", "row" -> {
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                    firstCellInRow = true
                                }
                                "tc", "table-cell", "td", "th" -> {
                                    if (!firstCellInRow) {
                                        out.append('\t')
                                    }
                                    firstCellInRow = false
                                    inTableCell = true
                                }
                                "r" -> {
                                    inRun = true
                                    rPrBold = false
                                    rPrItalic = false
                                    rPrUnderline = false
                                    rPrStrike = false
                                    rPrVertAlign = ""
                                    runTextBuilder.setLength(0)
                                }
                                "rpr" -> {
                                    if (inRun) inRPr = true
                                }
                                "b", "strong" -> {
                                    if (inRPr) {
                                        val valAttr = parser.getAttributeValue(null, "val")
                                        rPrBold = (valAttr == null || (valAttr != "0" && !valAttr.equals("false", true)))
                                    } else if (!inRun) {
                                        out.append("<b>")
                                    }
                                }
                                "i", "emphasis", "em" -> {
                                    if (inRPr) {
                                        val valAttr = parser.getAttributeValue(null, "val")
                                        rPrItalic = (valAttr == null || (valAttr != "0" && !valAttr.equals("false", true)))
                                    } else if (!inRun) {
                                        out.append("<i>")
                                    }
                                }
                                "u" -> {
                                    if (inRPr) {
                                        val valAttr = parser.getAttributeValue(null, "val")
                                        rPrUnderline = (valAttr == null || (!valAttr.equals("none", true) && valAttr != "0"))
                                    } else if (!inRun) {
                                        out.append("<u>")
                                    }
                                }
                                "strike", "dstrike", "s" -> {
                                    if (inRPr) {
                                        val valAttr = parser.getAttributeValue(null, "val")
                                        rPrStrike = (valAttr == null || (valAttr != "0" && !valAttr.equals("false", true)))
                                    } else if (localName == "s" && !inRun) {
                                        val c = (parser.getAttributeValue(null, "c")?.toIntOrNull() ?: 0)
                                        if (c > 0) {
                                            repeat(c.coerceIn(1, 32)) { out.append(' ') }
                                        } else {
                                            out.append("<s>")
                                        }
                                    }
                                }
                                "vertalign" -> {
                                    if (inRPr) {
                                        val v = parser.getAttributeValue(null, "val")?.lowercase(Locale.ROOT) ?: ""
                                        rPrVertAlign = when (v) {
                                            "subscript" -> "sub"
                                            "superscript" -> "sup"
                                            else -> ""
                                        }
                                    }
                                }
                                "sym" -> {
                                    val charHex = parser.getAttributeValue(null, "char")
                                    if (!charHex.isNullOrBlank()) {
                                        val code = charHex.toIntOrNull(16)
                                        if (code != null) {
                                            val mapped = if (code in 0xF020..0xF0FF) (code - 0xF000).toChar() else code.toChar()
                                            if (inRun) runTextBuilder.append(mapped) else out.append(mapped)
                                        }
                                    }
                                }
                                "t" -> {
                                    if (inRun) inRunText = true
                                }
                                "tab" -> {
                                    if (inRun) runTextBuilder.append('\t') else out.append('\t')
                                }
                                "br", "line-break" -> {
                                    if (inRun) {
                                        runTextBuilder.append(if (inTableCell) ' ' else '\n')
                                    } else {
                                        out.append(if (inTableCell) ' ' else '\n')
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inBinaryTag) {
                            if (binaryBase64Builder.length < 24 * 1024 * 1024) {
                                binaryBase64Builder.append(parser.text ?: "")
                            }
                        } else if (ignoreDepth == 0) {
                            val rawText = parser.text ?: ""
                            if (inRun) {
                                if (inRunText && runTextBuilder.length < 65536) {
                                    runTextBuilder.append(rawText)
                                }
                            } else {
                                val normalized = rawText.replace(Regex("[\\r\\n\\t]+"), " ")
                                if (normalized.isNotBlank()) {
                                    val trimmed = normalized.trim()
                                    if (out.isNotEmpty()) {
                                        val lastCh = out.last()
                                        if (lastCh != '\n' && lastCh != '\t' && lastCh != ' ' && lastCh != '>' &&
                                            !trimmed.startsWith(",") && !trimmed.startsWith(".") &&
                                            !trimmed.startsWith(";") && !trimmed.startsWith(":") &&
                                            !trimmed.startsWith("!") && !trimmed.startsWith("?")
                                        ) {
                                            out.append(' ')
                                        }
                                    }
                                    out.append(trimmed)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (inBinaryTag && (localName == "binary" || localName == "binarydata")) {
                            inBinaryTag = false
                            if (onBinaryImageDecoded != null && binaryBase64Builder.length in 32..(24 * 1024 * 1024)) {
                                try {
                                    val decoded = Base64.decode(binaryBase64Builder.toString(), Base64.DEFAULT)
                                    if (decoded.isNotEmpty()) {
                                        onBinaryImageDecoded(decoded)
                                    }
                                } catch (_: Throwable) {}
                            }
                            binaryBase64Builder.setLength(0)
                        } else if (ignoreDepth > 0) {
                            ignoreDepth--
                        } else {
                            when (localName) {
                                "t" -> inRunText = false
                                "rpr" -> inRPr = false
                                "r" -> {
                                    if (inRun) {
                                        val runStr = runTextBuilder.toString()
                                        if (runStr.isNotEmpty()) {
                                            if (rPrBold) out.append("<b>")
                                            if (rPrItalic) out.append("<i>")
                                            if (rPrUnderline) out.append("<u>")
                                            if (rPrStrike) out.append("<s>")
                                            if (rPrVertAlign.isNotEmpty()) out.append("<$rPrVertAlign>")
                                            out.append(runStr)
                                            if (rPrVertAlign.isNotEmpty()) out.append("</$rPrVertAlign>")
                                            if (rPrStrike) out.append("</s>")
                                            if (rPrUnderline) out.append("</u>")
                                            if (rPrItalic) out.append("</i>")
                                            if (rPrBold) out.append("</b>")
                                        }
                                        inRun = false
                                        inRPr = false
                                        rPrBold = false
                                        rPrItalic = false
                                        rPrUnderline = false
                                        rPrStrike = false
                                        rPrVertAlign = ""
                                        runTextBuilder.setLength(0)
                                    }
                                }
                                "b", "strong" -> if (!inRPr && !inRun) out.append("</b>")
                                "i", "emphasis", "em" -> if (!inRPr && !inRun) out.append("</i>")
                                "u" -> if (!inRPr && !inRun) out.append("</u>")
                                "h1", "title", "h2", "h3", "subtitle", "h" -> {
                                    if (activeHeadingLevel == 1) out.append("</h1>")
                                    else if (activeHeadingLevel == 2) out.append("</h2>")
                                    activeHeadingLevel = 0
                                    if (!out.endsWith("\n")) out.append('\n')
                                }
                                "tc", "table-cell", "td", "th" -> {
                                    while (out.isNotEmpty() && out.last() == ' ') {
                                        out.deleteCharAt(out.length - 1)
                                    }
                                    inTableCell = false
                                }
                                "tr", "table-row", "row" -> {
                                    inTableCell = false
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                }
                                "li", "list-item" -> {
                                    inListItem = false
                                    if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                                }
                                in blockBreakTags -> {
                                    if (localName == "p") {
                                        inRun = false
                                        inRPr = false
                                        rPrBold = false
                                        rPrItalic = false
                                        rPrUnderline = false
                                        rPrStrike = false
                                        rPrVertAlign = ""
                                        if (activeHeadingLevel == 1) {
                                            out.append("</h1>")
                                            activeHeadingLevel = 0
                                        } else if (activeHeadingLevel == 2) {
                                            out.append("</h2>")
                                            activeHeadingLevel = 0
                                        }
                                        inListItem = false
                                        listIndentLevel = 0
                                    }
                                    if (inTableCell) {
                                        if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\t') {
                                            out.append(' ')
                                        }
                                    } else {
                                        val isEmptyParagraph = localName == "p" && paragraphStartLength == out.length
                                        if (out.isNotEmpty() && !out.endsWith("\n")) {
                                            out.append('\n')
                                        } else if (isEmptyParagraph && out.isNotEmpty() && !out.endsWith("\n\n")) {
                                            out.append('\n')
                                        }
                                    }
                                    if (localName == "p") paragraphStartLength = -1
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        private val INLINE_IMAGE_TAG_REGEX = Regex("""\[IMAGE:(\d+)]""")

        /**
         * Paginates formatted rich text into full-screen pages.
         * Uses calibrated document line density (`charsPerVisualLine = 68`, `maxVisualLinesPerPage = 46`)
         * so that even fallback raster pages fill 100% of the vertical screen height.
         */
        fun paginateRichText(full: String, imageCount: Int, targetPages: MutableList<String>) {
            targetPages.clear()
            val cleaned = full.replace("\r\n", "\n").replace('\r', '\n').trim()
            if (cleaned.isEmpty()) {
                if (imageCount > 0) {
                    for (i in 0 until imageCount) {
                        targetPages.add("[IMAGE:$i]")
                    }
                } else {
                    targetPages.add("")
                }
                return
            }

            val rawLines = cleaned.split('\n')
            val charsPerVisualLine = 68
            val maxVisualLinesPerPage = 46
            val imageVisualLinesCost = 14

            val currentPageLines = ArrayList<String>()
            var currentVisualLines = 0
            var imagesOnCurrentPage = 0

            fun flushCurrentPage() {
                if (currentPageLines.isNotEmpty()) {
                    targetPages.add(currentPageLines.joinToString("\n").trim())
                    currentPageLines.clear()
                    currentVisualLines = 0
                    imagesOnCurrentPage = 0
                }
            }

            for (rawLine in rawLines) {
                val trimmed = rawLine.trimEnd()
                if (trimmed.isEmpty()) continue

                if (INLINE_IMAGE_TAG_REGEX.matches(trimmed.trim())) {
                    if (imagesOnCurrentPage >= 1 ||
                        (currentVisualLines > 0 && currentVisualLines + imageVisualLinesCost > maxVisualLinesPerPage)
                    ) {
                        flushCurrentPage()
                    }
                    currentPageLines.add(trimmed.trim())
                    currentVisualLines += imageVisualLinesCost
                    imagesOnCurrentPage++
                    continue
                }

                val isTableLine = trimmed.contains('\t')
                val isHeading1 = trimmed.contains("<h1>")
                val isHeading2 = trimmed.contains("<h2>")

                val plainVisible = trimmed
                    .replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
                    .replace(INLINE_IMAGE_TAG_REGEX, "")

                val lineVisualCost = when {
                    isTableLine -> 2
                    isHeading1 -> maxOf(2, (plainVisible.length + 39) / 40 + 1)
                    isHeading2 -> maxOf(2, (plainVisible.length + 49) / 50 + 1)
                    else -> maxOf(1, (plainVisible.length + charsPerVisualLine - 1) / charsPerVisualLine)
                }

                if (lineVisualCost > maxVisualLinesPerPage && !isTableLine) {
                    flushCurrentPage()
                    appendSplitOverlongBlock(trimmed, charsPerVisualLine * maxVisualLinesPerPage, targetPages)
                    continue
                }

                if (currentVisualLines + lineVisualCost > maxVisualLinesPerPage && currentPageLines.isNotEmpty()) {
                    flushCurrentPage()
                }

                currentPageLines.add(trimmed)
                currentVisualLines += lineVisualCost
            }

            flushCurrentPage()
            if (targetPages.isEmpty()) {
                targetPages.add("")
            }
        }

        private fun appendSplitOverlongBlock(
            block: String,
            maxCharsPerPage: Int,
            targetPages: MutableList<String>
        ) {
            val plain = block.replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
            var start = 0
            while (start < plain.length) {
                var end = minOf(start + maxCharsPerPage, plain.length)
                if (end < plain.length) {
                    val spaceIdx = plain.lastIndexOf(' ', end)
                    if (spaceIdx > start + maxCharsPerPage / 2) {
                        end = spaceIdx
                    }
                }
                val slice = plain.substring(start, end).trim()
                if (slice.isNotEmpty()) {
                    targetPages.add(slice)
                }
                start = if (end > start) end else start + maxCharsPerPage
                while (start < plain.length && plain[start] == ' ') start++
            }
        }

        /**
         * Renders a rich-formatted document page (`<h1>`, `<h2>`, `<b>`, `<i>`, `<u>`, `<s>`,
         * bullets `• `, tables with `\t`, and `[IMAGE:n]` illustrations) onto [canvas].
         */
        fun renderRichDocumentPageToCanvas(
            canvas: Canvas,
            bitmapWidth: Int,
            bitmapHeight: Int,
            pageIndex: Int,
            tileX: Int,
            tileY: Int,
            zoom: Float,
            isLightTheme: Boolean,
            pageText: String,
            embeddedImages: List<Bitmap>,
            baseTextPaint: TextPaint
        ) {
            val isIllustrationOnly = (pageIndex and ILLUSTRATION_ONLY_PAGE_FLAG) != 0
            val cleanPageIndex = (pageIndex and PAGE_INDEX_MASK).coerceAtLeast(0)

            val pageBgColor = if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C)
            val bodyTextColor = if (isLightTheme) Color.rgb(30, 32, 38) else Color.rgb(0xEC, 0xEF, 0xF4)
            val headingTextColor = if (isLightTheme) Color.rgb(18, 24, 42) else Color.rgb(0x89, 0xB4, 0xFA)
            val tableHeaderBg = if (isLightTheme) Color.rgb(232, 236, 245) else Color.rgb(0x26, 0x29, 0x3A)
            val tableCellBg = if (isLightTheme) Color.WHITE else Color.rgb(0x1E, 0x1E, 0x2E)
            val tableBorderCol = if (isLightTheme) Color.rgb(180, 188, 204) else Color.rgb(0x3B, 0x3E, 0x52)

            canvas.drawColor(pageBgColor)

            val imgMatch = INLINE_IMAGE_TAG_REGEX.find(pageText)
            val matchedImgIdx = imgMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val pageImg: Bitmap? = when {
                isIllustrationOnly -> embeddedImages.getOrNull(cleanPageIndex)
                matchedImgIdx != null -> embeddedImages.getOrNull(matchedImgIdx)
                else -> null
            }

            if (isIllustrationOnly) {
                if (pageImg != null && !pageImg.isRecycled) {
                    val pad = 16f
                    val availW = (bitmapWidth - pad * 2f).coerceAtLeast(40f)
                    val availH = (bitmapHeight - pad * 2f).coerceAtLeast(40f)
                    val aspect = pageImg.width.toFloat() / pageImg.height.toFloat().coerceAtLeast(1f)
                    var drawW = availW
                    var drawH = drawW / aspect
                    if (drawH > availH) {
                        drawH = availH
                        drawW = drawH * aspect
                    }
                    val left = (bitmapWidth - drawW) / 2f
                    val top = (bitmapHeight - drawH) / 2f
                    val dst = RectF(left, top, left + drawW, top + drawH)
                    canvas.drawBitmap(pageImg, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                }
                return
            }

            val marginH = 40f
            val marginV = 36f
            val contentWidth = (bitmapWidth - (marginH * 2).toInt()).coerceAtLeast(200)

            canvas.save()
            canvas.translate(marginH - tileX, marginV - tileY)
            canvas.scale(zoom, zoom)

            var curY = 0f
            val baseFontSize = 23f
            val tableFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val tableGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = tableBorderCol
            }

            val rawLines = pageText.split('\n')
            var lineIdx = 0
            while (lineIdx < rawLines.size) {
                val line = rawLines[lineIdx].trimEnd()
                if (line.isBlank()) {
                    curY += 10f
                    lineIdx++
                    continue
                }

                val inlineImg = INLINE_IMAGE_TAG_REGEX.matchEntire(line.trim())
                if (inlineImg != null) {
                    val idx = inlineImg.groupValues[1].toIntOrNull() ?: -1
                    val bmp = embeddedImages.getOrNull(idx)
                    if (bmp != null && !bmp.isRecycled) {
                        val maxImgH = (bitmapHeight * 0.34f).coerceIn(160f, 400f)
                        val aspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                        var drawW = contentWidth.toFloat() * 0.88f
                        var drawH = drawW / aspect
                        if (drawH > maxImgH) {
                            drawH = maxImgH
                            drawW = drawH * aspect
                        }
                        val left = (contentWidth - drawW) / 2f
                        val dst = RectF(left, curY + 8f, left + drawW, curY + 8f + drawH)
                        canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                        curY += drawH + 18f
                    }
                    lineIdx++
                    continue
                }

                if (line.contains('\t')) {
                    val tableBlock = ArrayList<List<String>>()
                    while (lineIdx < rawLines.size && rawLines[lineIdx].contains('\t')) {
                        val cells = rawLines[lineIdx]
                            .replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
                            .split('\t')
                            .map { it.trim() }
                        if (cells.any { it.isNotEmpty() }) {
                            tableBlock.add(cells)
                        }
                        lineIdx++
                    }
                    if (tableBlock.isNotEmpty()) {
                        val colCount = tableBlock.maxOf { it.size }.coerceIn(1, 12)
                        val colW = contentWidth.toFloat() / colCount
                        val cellPaint = TextPaint(baseTextPaint).apply {
                            textSize = 20f
                            color = bodyTextColor
                        }
                        for ((rIdx, rowCells) in tableBlock.withIndex()) {
                            var rowMaxH = 36f
                            val cellLayouts = ArrayList<StaticLayout?>(colCount)
                            for (cIdx in 0 until colCount) {
                                val cellTxt = rowCells.getOrNull(cIdx) ?: ""
                                cellPaint.isFakeBoldText = (rIdx == 0)
                                if (cellTxt.isNotEmpty()) {
                                    val l = StaticLayout.Builder
                                        .obtain(cellTxt, 0, cellTxt.length, cellPaint, (colW - 14f).toInt().coerceAtLeast(24))
                                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                        .setIncludePad(false)
                                        .build()
                                    cellLayouts.add(l)
                                    rowMaxH = maxOf(rowMaxH, l.height + 14f)
                                } else {
                                    cellLayouts.add(null)
                                }
                            }
                            for (cIdx in 0 until colCount) {
                                val cellRect = RectF(cIdx * colW, curY, (cIdx + 1) * colW, curY + rowMaxH)
                                tableFillPaint.color = if (rIdx == 0) tableHeaderBg else tableCellBg
                                canvas.drawRect(cellRect, tableFillPaint)
                                canvas.drawRect(cellRect, tableGridPaint)
                                val layout = cellLayouts[cIdx]
                                if (layout != null) {
                                    canvas.save()
                                    canvas.clipRect(cellRect)
                                    canvas.translate(cellRect.left + 7f, cellRect.top + 7f)
                                    layout.draw(canvas)
                                    canvas.restore()
                                }
                            }
                            curY += rowMaxH
                        }
                        curY += 14f
                    }
                    continue
                }

                val isH1 = line.contains("<h1>")
                val isH2 = line.contains("<h2>")
                val hasBold = line.contains("<b>")
                val hasItalic = line.contains("<i>")
                val hasUnderline = line.contains("<u>")
                val hasStrike = line.contains("<s>")

                val cleanLine = line
                    .replace(Regex("</?(h1|h2|b|i|u|s|sub|sup)>"), "")
                    .replace(INLINE_IMAGE_TAG_REGEX, "")
                    .trim()

                if (cleanLine.isNotEmpty()) {
                    val paraPaint = TextPaint(baseTextPaint).apply {
                        color = if (isH1 || isH2) headingTextColor else bodyTextColor
                        textSize = when {
                            isH1 -> baseFontSize * 1.32f
                            isH2 -> baseFontSize * 1.18f
                            else -> baseFontSize
                        }
                        typeface = when {
                            (isH1 || isH2 || hasBold) && hasItalic -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
                            isH1 || isH2 || hasBold -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                            hasItalic -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                            else -> Typeface.SERIF
                        }
                        isUnderlineText = hasUnderline
                        isStrikeThruText = hasStrike
                    }

                    if (isH1 || isH2) {
                        curY += 6f
                    }

                    val layout = StaticLayout.Builder
                        .obtain(cleanLine, 0, cleanLine.length, paraPaint, contentWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(3f, 1.12f)
                        .setIncludePad(false)
                        .build()

                    canvas.save()
                    canvas.translate(0f, curY)
                    layout.draw(canvas)
                    canvas.restore()

                    curY += layout.height + if (isH1 || isH2) 14f else 8f
                }

                lineIdx++
            }

            canvas.restore()
        }
    }

    override val pageCount: Int get() = pages.size.coerceAtLeast(1)
    override fun getLastError(): String? = parseError

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
        if (parseError != null) {
            bitmap.eraseColor(if (isLightTheme) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
            return
        }
        val isIllustrationOnly = (pageIndex and ILLUSTRATION_ONLY_PAGE_FLAG) != 0
        val cleanPageIndex = (pageIndex and PAGE_INDEX_MASK).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val effectivePageIndex = if (isIllustrationOnly) {
            (pageIndex and LIGHT_THEME_PAGE_FLAG.inv())
        } else {
            cleanPageIndex
        }
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 5f)
        val canvas = Canvas(bitmap)

        renderRichDocumentPageToCanvas(
            canvas = canvas,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            pageIndex = effectivePageIndex,
            tileX = tileX,
            tileY = tileY,
            zoom = zoom,
            isLightTheme = isLightTheme,
            pageText = getPageText(cleanPageIndex),
            embeddedImages = embeddedImages,
            baseTextPaint = textPaint
        )
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        val safeStart = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        for (i in safeStart until pages.size) {
            if (pages[i].contains(query, ignoreCase = true)) return i.toLong()
        }
        for (i in 0 until safeStart) {
            if (pages[i].contains(query, ignoreCase = true)) return i.toLong()
        }
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

private val NAMED_HTML_ENTITIES = mapOf(
    "nbsp" to " ", "quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'",
    "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "hellip" to "…",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„", "sbquo" to "‚",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•",
    "shy" to "", "times" to "×", "euro" to "€", "pound" to "£", "sect" to "§", "para" to "¶",
    "iexcl" to "¡", "iquest" to "¿", "thinsp" to " ", "ensp" to " ", "emsp" to " "
)

private val HTML_ENTITY_REGEX = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,10});")

internal fun decodeHtmlEntities(text: String): String = HTML_ENTITY_REGEX.replace(text) { m ->
    val body = m.groupValues[1]
    when {
        body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)
            ?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
        body.startsWith("#") -> body.substring(1).toIntOrNull()
            ?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
        else -> NAMED_HTML_ENTITIES[body] ?: NAMED_HTML_ENTITIES[body.lowercase()] ?: m.value
    }
}

fun stripMobiHtmlToRichText(html: String, imageCount: Int): String {
    val withBreaks = html
        .replace(Regex("<head[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<(br|p|div|tr|li|blockquote)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<h1[^>]*>", RegexOption.IGNORE_CASE), "\n<h1>")
        .replace(Regex("</h1>", RegexOption.IGNORE_CASE), "</h1>\n")
        .replace(Regex("<h[2-4][^>]*>", RegexOption.IGNORE_CASE), "\n<h2>")
        .replace(Regex("</h[2-4]>", RegexOption.IGNORE_CASE), "</h2>\n")
        .replace(Regex("<(b|strong)[^>]*>", RegexOption.IGNORE_CASE), "<b>")
        .replace(Regex("</(b|strong)>", RegexOption.IGNORE_CASE), "</b>")
        .replace(Regex("<(i|em)[^>]*>", RegexOption.IGNORE_CASE), "<i>")
        .replace(Regex("</(i|em)>", RegexOption.IGNORE_CASE), "</i>")
        .replace(Regex("<(?!/?(?:h1|h2|b|i)>)[^>]+>"), "")
        .let { decodeHtmlEntities(it) }

    val sb = StringBuilder()
    if (imageCount > 0) {
        sb.append("[IMAGE:0]\n")
    }
    for (line in withBreaks.lines()) {
        val trimmed = line.trim()
        if (trimmed.isNotEmpty()) {
            sb.append(trimmed).append('\n')
        }
    }
    for (imgIdx in 1 until imageCount) {
        sb.append("[IMAGE:$imgIdx]\n")
    }
    return sb.toString().trim()
}

class KindleDrmNoticeSession(
    private val pfd: ParcelFileDescriptor,
    private val format: DetectedFormat
) : DocumentSession {
    @Volatile private var isClosed = false
    override val pageCount: Int = 1

    override fun getLastError(): String =
        "DRM_PROTECTED: This Kindle e-book (${format.name}) is protected by cryptographic DRM. " +
                "Circumventing DRM protection is prohibited under DMCA § 1201 and the EU InfoSoc Directive."

    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLight = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        bitmap.eraseColor(if (isLight) Color.rgb(250, 248, 242) else Color.rgb(0x14, 0x15, 0x1C))
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}
