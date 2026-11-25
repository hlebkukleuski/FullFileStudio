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

package me.hlebkukleuski.fullfilestudio.core.document.word

import java.util.concurrent.ConcurrentHashMap
import me.hlebkukleuski.fullfilestudio.core.document.OfficeFonts

internal object WordFontMetrics {

    private val metricsCache = ConcurrentHashMap<String, FloatArray>()

    fun clearCache() {
        metricsCache.clear()
    }

    fun of(name: String?): FloatArray? {
        val family = OfficeFonts.bundledFamily(name) ?: return null
        metricsCache[family]?.let { return it }
        val bytes = OfficeFonts.readFontBytes(name, bold = false, italic = false) ?: return null
        val parsed = parseOpenTypeVerticalMetrics(bytes) ?: return null
        metricsCache[family] = parsed
        return parsed
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    internal fun parseOpenTypeVerticalMetrics(data: ByteArray): FloatArray? {
        if (data.size < 12) return null
        val numTables = u16(data, 4)
        if (12 + numTables * 16 > data.size) return null

        var headOff = -1
        var hheaOff = -1
        var os2Off = -1
        var os2Len = 0
        for (i in 0 until numTables) {
            val rec = 12 + i * 16
            val tag = u32(data, rec)
            val off = u32(data, rec + 8)
            val len = u32(data, rec + 12)
            if (off < 0 || len < 0 || off + len > data.size) continue
            when (tag) {
                0x68656164 -> headOff = off
                0x68686561 -> hheaOff = off
                0x4F532F32 -> { os2Off = off; os2Len = len }
            }
        }
        if (headOff < 0 || headOff + 20 > data.size) return null
        val upm = u16(data, headOff + 18)
        if (upm <= 0) return null
        val upmF = upm.toFloat()

        if (hheaOff >= 0 && hheaOff + 10 <= data.size) {
            var asc = s16(data, hheaOff + 4).coerceAtLeast(0) / upmF
            var desc = (-s16(data, hheaOff + 6)).coerceAtLeast(0) / upmF
            var gap = s16(data, hheaOff + 8).coerceAtLeast(0) / upmF
            if (asc + desc + gap > 1.32f && os2Off >= 0 && os2Len >= 74 && os2Off + 74 <= data.size) {
                val typoAsc = s16(data, os2Off + 68).coerceAtLeast(0) / upmF
                val typoDesc = (-s16(data, os2Off + 70)).coerceAtLeast(0) / upmF
                val typoGap = s16(data, os2Off + 72).coerceAtLeast(0) / upmF
                if (typoAsc > 0f && typoGap > 0f && (typoAsc + typoDesc + typoGap) in 1.12f..1.30f) {
                    asc = typoAsc
                    desc = typoDesc
                    gap = typoGap
                }
            }
            if (asc + desc > 0f) {
                if (asc + desc + gap < 1.12f) {
                    gap = (1.18f - asc - desc).coerceAtLeast(gap)
                }
                return floatArrayOf(asc, desc, gap)
            }
        }

        if (os2Off >= 0 && os2Len >= 78 && os2Off + 78 <= data.size) {
            val winAsc = u16(data, os2Off + 74) / upmF
            val winDesc = u16(data, os2Off + 76) / upmF
            if (winAsc + winDesc > 0f) return floatArrayOf(winAsc, winDesc, 0f)
        }
        return null
    }
}
