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

package com.omnireader.core.engine

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * First-line anti-exploit gatekeeper that inspects file headers before handing descriptors to format parsers.
 * Blocks executable binaries, polyglot injections, and XML External Entity (XXE) payloads.
 */
object MaliciousHeaderSanitizer {

    private val DOCTYPE_TAG_REGEX = Regex("""<!doctype\s+[^>]*?(?:\[[\s\S]*?\]\s*>|>)""", RegexOption.IGNORE_CASE)
    private val ENTITY_TAG_REGEX = Regex("""<!entity\s+[^>]*>""", RegexOption.IGNORE_CASE)

    data class SanitizationReport(
        val isSafe: Boolean,
        val threatDescription: String? = null
    )

    fun inspect(buffer: ByteBuffer, format: DetectedFormat): SanitizationReport {
        val limit = buffer.limit()
        if (limit <= 0) return SanitizationReport(isSafe = true)

        if (format == DetectedFormat.ELF || format == DetectedFormat.DEX) {
            return SanitizationReport(
                isSafe = false,
                threatDescription = "Executable machine code ($format) detected. Direct execution is blocked."
            )
        }

        if (format == DetectedFormat.CORRUPTED_CONTAINER) {
            return SanitizationReport(
                isSafe = false,
                threatDescription = "Corrupted or malformed archive container header."
            )
        }

        val savedPos = buffer.position()
        val savedOrder = buffer.order()
        try {
            buffer.order(ByteOrder.BIG_ENDIAN)

            if (limit >= 4) {
                val m32 = buffer.getInt(0)
                // ELF and 32/64-bit Mach-O magic headers, plus verified DOS/PE ("MZ") binary headers
                val isElfOrMachO = m32 == 0x7F454C46 ||
                        m32 == -0x01120532 ||
                        m32 == -0x01120531 ||
                        m32 == -0x31051202 ||
                        m32 == -0x30051202
                val isPeMz = ((m32 ushr 16) == 0x4D5A) && isLikelyMzBinaryExecutable(buffer, limit)

                if (isElfOrMachO || isPeMz) {
                    return SanitizationReport(
                        isSafe = false,
                        threatDescription = "Executable binary header detected (PE/Mach-O/ELF)."
                    )
                }
            }

            // Reject PDF headers containing embedded ZIP or ELF polyglot signatures in the first 1 KB
            if (format == DetectedFormat.PDF) {
                val checkLimit = minOf(limit, 1024)
                for (i in 4 until checkLimit - 4) {
                    val sig = buffer.getInt(i)
                    if (sig == 0x504B0304 || sig == 0x7F454C46) {
                        return SanitizationReport(
                            isSafe = false,
                            threatDescription = "Polyglot container injection detected inside PDF header."
                        )
                    }
                }
            }

            if (format in setOf(
                    DetectedFormat.SVG,
                    DetectedFormat.SVGZ,
                    DetectedFormat.FB2,
                    DetectedFormat.OFFICE_XML
                )
            ) {
                val scanSize = minOf(limit, 4096)
                val rawBytes = ByteArray(scanSize)
                buffer.position(0)
                buffer.get(rawBytes)

                val decodedBytes = if (format == DetectedFormat.SVGZ ||
                    (rawBytes.size >= 2 && rawBytes[0] == 0x1F.toByte() && rawBytes[1] == 0x8B.toByte())
                ) {
                    decompressGzipPrefix(rawBytes, 16 * 1024) ?: rawBytes
                } else {
                    rawBytes
                }

                if (containsXxePayload(decodedBytes)) {
                    return SanitizationReport(
                        isSafe = false,
                        threatDescription = "Blocked potential XXE injection (external XML entities and DTDs are forbidden)."
                    )
                }
            }

            return SanitizationReport(isSafe = true)
        } finally {
            buffer.position(savedPos)
            buffer.order(savedOrder)
        }
    }

    /**
     * Distinguishes a real DOS/PE executable header ("MZ") from a plain text file that happens to start with "MZ".
     */
    private fun isLikelyMzBinaryExecutable(buffer: ByteBuffer, limit: Int): Boolean {
        val checkLen = minOf(limit, 64)
        for (i in 2 until checkLen) {
            val b = buffer.get(i).toInt() and 0xFF
            if (b == 0 || (b in 1..8) || (b in 14..31)) {
                return true
            }
        }
        if (limit >= 64) {
            val peOffset = Integer.reverseBytes(buffer.getInt(60))
            if (peOffset in 64..(limit - 4)) {
                val peSig = buffer.getInt(peOffset)
                if (peSig == 0x50450000) return true
            }
        }
        return false
    }

    private fun decompressGzipPrefix(compressed: ByteArray, maxOut: Int): ByteArray? {
        return try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gis ->
                val buf = ByteArray(maxOut)
                var total = 0
                while (total < maxOut) {
                    val r = gis.read(buf, total, maxOut - total)
                    if (r <= 0) break
                    total += r
                }
                if (total > 0) buf.copyOf(total) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun containsXxePayload(bytes: ByteArray): Boolean {
        val candidates = listOf(
            String(bytes, StandardCharsets.UTF_8).lowercase(Locale.ROOT),
            String(bytes, StandardCharsets.UTF_16LE).lowercase(Locale.ROOT),
            String(bytes, StandardCharsets.UTF_16BE).lowercase(Locale.ROOT)
        )
        return candidates.any { headStr ->
            if (!headStr.contains("<!entity") && !headStr.contains("<!doctype")) {
                return@any false
            }

            // 1. Inspect any <!ENTITY ...> declarations for external URIs, parameter entities, or entity expansion bombs
            if (headStr.contains("<!entity")) {
                val entityMatches = ENTITY_TAG_REGEX.findAll(headStr).toList()
                if (entityMatches.isEmpty()) {
                    return@any true
                }
                val hasDangerousEntity = entityMatches.any { match ->
                    val tag = match.value
                    tag.contains("system") ||
                            tag.contains("public") ||
                            tag.contains("%") ||
                            tag.contains("&") ||
                            tag.contains("file:") ||
                            tag.contains("http://") ||
                            tag.contains("https://") ||
                            tag.contains("ftp://") ||
                            tag.contains("jar:") ||
                            tag.contains("netdoc:") ||
                            tag.contains("gopher:") ||
                            tag.contains("expect:") ||
                            tag.contains("php:") ||
                            tag.contains("data:")
                }
                if (hasDangerousEntity) return@any true
            }

            // 2. Inspect any <!DOCTYPE ...> declarations for dangerous external URI schemes or untrusted SYSTEM DTDs
            for (match in DOCTYPE_TAG_REGEX.findAll(headStr)) {
                val doctype = match.value
                val hasMaliciousUri = doctype.contains("file:") ||
                        doctype.contains("jar:") ||
                        doctype.contains("netdoc:") ||
                        doctype.contains("gopher:") ||
                        doctype.contains("expect:") ||
                        doctype.contains("php:") ||
                        doctype.contains("data:")
                if (hasMaliciousUri) return@any true

                val doctypeHeaderOnly = doctype.substringBefore('[')
                if (doctypeHeaderOnly.contains("system")) {
                    val isStandardPublicDtd = doctypeHeaderOnly.contains("public") &&
                            (doctypeHeaderOnly.contains("-//w3c//dtd") ||
                                    doctypeHeaderOnly.contains("-//oasis//dtd") ||
                                    doctypeHeaderOnly.contains("svg") ||
                                    doctypeHeaderOnly.contains("xhtml"))
                    val isLocalDtdFilename = !doctypeHeaderOnly.contains("://") && !doctypeHeaderOnly.contains("file:")
                    if (!isStandardPublicDtd && !isLocalDtdFilename) return@any true
                }
            }

            // Catch truncated <!DOCTYPE declarations that already open an external SYSTEM/file reference
            val unclosedDoctypeIdx = headStr.indexOf("<!doctype")
            if (unclosedDoctypeIdx >= 0 && headStr.indexOf('>', unclosedDoctypeIdx) < 0) {
                val tail = headStr.substring(unclosedDoctypeIdx)
                if (tail.contains("<!entity") || tail.contains("file:") || tail.contains("jar:") || tail.contains("gopher:")) {
                    return@any true
                }
            }

            false
        }
    }

    fun isSafeToParse(buffer: ByteBuffer, format: DetectedFormat): Boolean {
        return inspect(buffer, format).isSafe
    }
}