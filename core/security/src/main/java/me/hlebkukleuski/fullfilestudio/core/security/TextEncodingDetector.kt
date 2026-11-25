/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.security

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object TextEncodingDetector {
    val CP1251: Charset = try { Charset.forName("windows-1251") } catch (_: Exception) { StandardCharsets.ISO_8859_1 }
    val KOI8_R: Charset = try { Charset.forName("KOI8-R") } catch (_: Exception) { StandardCharsets.ISO_8859_1 }
    val IBM866: Charset = try { Charset.forName("IBM866") } catch (_: Exception) { StandardCharsets.ISO_8859_1 }

    fun detectCharsetAndValidateText(sample: ByteArray, length: Int): Pair<Charset, Int> {
        if (length >= 3 &&
            (sample[0].toInt() and 0xFF) == 0xEF &&
            (sample[1].toInt() and 0xFF) == 0xBB &&
            (sample[2].toInt() and 0xFF) == 0xBF
        ) {
            return StandardCharsets.UTF_8 to 3
        }
        if (length >= 2 &&
            (sample[0].toInt() and 0xFF) == 0xFF &&
            (sample[1].toInt() and 0xFF) == 0xFE
        ) {
            return StandardCharsets.UTF_16LE to 2
        }
        if (length >= 2 &&
            (sample[0].toInt() and 0xFF) == 0xFE &&
            (sample[1].toInt() and 0xFF) == 0xFF
        ) {
            return StandardCharsets.UTF_16BE to 2
        }

        if (length >= 16) {
            var evenNulls = 0
            var oddNulls = 0
            val pairs = length / 2
            for (i in 0 until pairs * 2 step 2) {
                if (sample[i].toInt() == 0) evenNulls++
                if (sample[i + 1].toInt() == 0) oddNulls++
            }
            if (oddNulls > pairs * 0.6 && evenNulls < pairs * 0.1) {
                return StandardCharsets.UTF_16LE to 0
            }
            if (evenNulls > pairs * 0.6 && oddNulls < pairs * 0.1) {
                return StandardCharsets.UTF_16BE to 0
            }
        }

        var suspiciousControlBytes = 0
        for (i in 0 until length) {
            val b = sample[i].toInt() and 0xFF
            if (b == 0x00 || (b in 1..8) || (b in 14..31 && b != 27)) {
                suspiciousControlBytes++
            }
        }
        if (length > 0 && suspiciousControlBytes.toDouble() / length.toDouble() > 0.10) {
            throw IllegalArgumentException("Binary content is not a text document.")
        }

        if (isValidUtf8(sample, length)) {
            return StandardCharsets.UTF_8 to 0
        }

        return detectBest8BitCyrillicCharset(sample, length) to 0
    }

    private fun isValidUtf8(bytes: ByteArray, len: Int): Boolean {
        var i = 0
        while (i < len) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b <= 0x7F -> i++
                b in 0xC2..0xDF -> {
                    if (i + 1 >= len) return true
                    if ((bytes[i + 1].toInt() and 0xC0) != 0x80) return false
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    if (i + 2 >= len) return true
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80 || (b2 and 0xC0) != 0x80) return false
                    if (b == 0xE0 && b1 < 0xA0) return false
                    if (b == 0xED && b1 >= 0xA0) return false
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    if (i + 3 >= len) return true
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    val b3 = bytes[i + 3].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80 || (b2 and 0xC0) != 0x80 || (b3 and 0xC0) != 0x80) return false
                    i += 4
                }
                else -> return false
            }
        }
        return true
    }

    private fun detectBest8BitCyrillicCharset(sample: ByteArray, length: Int): Charset {
        var highByteCount = 0
        var cp1251Lower = 0
        var cp1251Upper = 0
        var ibm866BoxOrControl = 0

        for (i in 0 until length) {
            val b = sample[i].toInt() and 0xFF
            if (b >= 0x80) {
                highByteCount++
                if (b in 0xE0..0xFF) cp1251Lower++
                else if (b in 0xC0..0xDF) cp1251Upper++
                if (b in 0xB0..0xDF) ibm866BoxOrControl++
            }
        }
        if (highByteCount == 0) return StandardCharsets.UTF_8

        if (cp1251Lower + cp1251Upper > highByteCount * 0.70 && cp1251Lower >= cp1251Upper) {
            return CP1251
        }

        val candidates = listOf(CP1251, KOI8_R, IBM866)
        var bestCharset = CP1251
        var bestScore = Int.MIN_VALUE

        for (cs in candidates) {
            val decoded = String(sample, 0, length, cs)
            var score = 0
            for (ch in decoded) {
                when (ch) {
                    in 'а'..'я', 'ё' -> score += 3
                    in 'А'..'Я', 'Ё' -> score += 2
                    in '\u2500'..'\u257F' -> score -= 4
                }
            }
            if (cs == IBM866 && ibm866BoxOrControl > highByteCount / 3) {
                score -= ibm866BoxOrControl * 2
            }
            if (score > bestScore) {
                bestScore = score
                bestCharset = cs
            }
        }
        return bestCharset
    }
}
