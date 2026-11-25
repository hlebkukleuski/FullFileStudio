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
 * Binary hyphenation trie (.hyb) format parser adapted from Android Open Source Project
 * Minikin (frameworks/minikin/libs/minikin/Hyphenator.cpp),
 * Copyright (C) 2015 The Android Open Source Project, licensed under the Apache License, Version 2.0.
 * Modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.core.document.word

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object WordHyphenator {
    private class HybTable(
        private val alphaVer: Int,
        private val minCp: Int,
        private val maxCp: Int,
        private val alphaBytes: ByteArray?,
        private val alphaEntries: IntArray?,
        private val charMask: Int,
        private val linkShift: Int,
        private val linkMask: Int,
        private val patternShift: Int,
        private val trie: IntArray,
        private val patDesc: IntArray,
        private val patData: ByteArray,
        val minPrefix: Int,
        val minSuffix: Int
    ) {
        private fun codeOf(cp: Int): Int {
            if (alphaVer == 0) {
                val b = alphaBytes ?: return 0
                if (cp < minCp || cp >= maxCp) return 0
                return b[cp - minCp].toInt() and 0xFF
            }
            val arr = alphaEntries ?: return 0
            var lo = 0
            var hi = arr.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val k = arr[mid] ushr 11
                if (k < cp) lo = mid + 1 else if (k > cp) hi = mid - 1 else return arr[mid] and 0x7FF
            }
            return 0
        }

        fun breaks(word: String): IntArray? {
            val n = word.length
            if (n < minPrefix + minSuffix) return IntArray(0)
            val codes = IntArray(n + 2)
            for (i in 0 until n) {
                val ch = word[i]
                var c = codeOf(ch.code)
                if (c == 0) c = codeOf(ch.lowercaseChar().code)
                if (c == 0) return null
                codes[i + 1] = c
            }
            val vals = IntArray(n + 1)
            val m = codes.size
            for (i in 0 until m) {
                var node = 0
                for (j in i until m) {
                    val c = codes[j]
                    val idx = node + c
                    if (idx < 0 || idx >= trie.size) break
                    val entry = trie[idx]
                    if ((entry and charMask) != c) break
                    node = (entry and linkMask) ushr linkShift
                    if (node < 0 || node >= trie.size) break
                    val patIx = trie[node] ushr patternShift
                    if (patIx != 0 && patIx < patDesc.size) {
                        val p = patDesc[patIx]
                        val pLen = p ushr 26
                        val pShift = (p ushr 20) and 0x3F
                        val pOff = p and 0xFFFFF
                        val base = j + 1 - pLen - pShift
                        for (k in 0 until pLen) {
                            val pos = base + k
                            if (pos in 1 until n && pOff + k < patData.size) {
                                val v = patData[pOff + k].toInt() and 0xFF
                                if (v > vals[pos]) vals[pos] = v
                            }
                        }
                    }
                }
            }
            val out = ArrayList<Int>()
            for (k in minPrefix..(n - minSuffix)) {
                if ((vals[k] and 1) != 0) out.add(k)
            }
            return out.toIntArray()
        }
    }

    private val cache = ConcurrentHashMap<String, java.util.Optional<HybTable>>()

    @Volatile
    internal var hybDir: File = File("/system/usr/hyphen-data")

    internal fun clearCache() = cache.clear()

    private fun loadHyb(tag: String, minPre: Int, minSuf: Int): HybTable? = cache.getOrPut(tag) {
        java.util.Optional.ofNullable(readHybFile(File(hybDir, "hyph-$tag.hyb"), minPre, minSuf))
    }.orElse(null)

    internal fun parseHybBytes(bytes: ByteArray, minPre: Int, minSuf: Int): ((String) -> IntArray?)? =
        parseHyb(bytes, minPre, minSuf)?.let { table -> { w -> table.breaks(w) } }

    private fun readHybFile(file: File, minPre: Int, minSuf: Int): HybTable? {
        if (!file.isFile) return null
        return try {
            parseHyb(file.readBytes(), minPre, minSuf)
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseHyb(bytes: ByteArray, minPre: Int, minSuf: Int): HybTable? {
        return try {
            if (bytes.size < 24) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) != 0x62AD7968) return null
            val alphaOff = bb.getInt(8)
            val trieOff = bb.getInt(12)
            val patOff = bb.getInt(16)
            if (alphaOff < 24 || trieOff <= alphaOff || patOff <= trieOff || patOff + 16 > bytes.size) return null
            val aVer = bb.getInt(alphaOff)
            var minCp = 0
            var maxCp = 0
            var aBytes: ByteArray? = null
            var aEntries: IntArray? = null
            if (aVer == 0) {
                minCp = bb.getInt(alphaOff + 4)
                maxCp = bb.getInt(alphaOff + 8)
                val cnt = (maxCp - minCp).coerceAtLeast(0)
                if (alphaOff + 12 + cnt > trieOff) return null
                aBytes = bytes.copyOfRange(alphaOff + 12, alphaOff + 12 + cnt)
            } else if (aVer == 1) {
                val cnt = bb.getInt(alphaOff + 4)
                if (cnt < 0 || alphaOff + 8 + cnt * 4 > trieOff) return null
                aEntries = IntArray(cnt) { bb.getInt(alphaOff + 8 + it * 4) }
            } else return null
            val charMask = bb.getInt(trieOff + 4)
            val linkShift = bb.getInt(trieOff + 8)
            val linkMask = bb.getInt(trieOff + 12)
            val patShift = bb.getInt(trieOff + 16)
            val nTrie = bb.getInt(trieOff + 20)
            if (nTrie <= 0 || trieOff + 24 + nTrie * 4 > patOff) return null
            val trie = IntArray(nTrie) { bb.getInt(trieOff + 24 + it * 4) }
            val nPat = bb.getInt(patOff + 4)
            val pDataOff = patOff + bb.getInt(patOff + 8)
            val pDataLen = bb.getInt(patOff + 12)
            if (nPat < 0 || patOff + 16 + nPat * 4 > bytes.size || pDataOff + pDataLen > bytes.size) return null
            val patDesc = IntArray(nPat) { bb.getInt(patOff + 16 + it * 4) }
            val patData = bytes.copyOfRange(pDataOff, pDataOff + pDataLen)
            HybTable(aVer, minCp, maxCp, aBytes, aEntries, charMask, linkShift, linkMask, patShift, trie, patDesc, patData, minPre, minSuf)
        } catch (_: Throwable) {
            null
        }
    }

    private fun tagFor(lid: Int, word: String): Triple<String, Int, Int> {
        val cyr = word.any { it in '\u0400'..'\u04FF' }
        val lang = lid and 0x3FF
        return when {
            lang == 0x19 || (lang == 0 && cyr) -> Triple("ru", 2, 2)
            lang == 0x22 -> Triple("uk", 2, 2)
            lang == 0x23 -> Triple("be", 2, 2)
            lang == 0x02 -> Triple("bg", 2, 2)
            lid == 0x0809 -> Triple("en-gb", 2, 3)
            lang == 0x09 -> Triple("en-us", 2, 3)
            lang == 0x07 -> Triple("de-1996", 2, 2)
            lang == 0x0C -> Triple("fr", 2, 2)
            lang == 0x0A -> Triple("es", 2, 2)
            lang == 0x10 -> Triple("it", 2, 2)
            lang == 0x15 -> Triple("pl", 2, 2)
            lang == 0x05 -> Triple("cs", 2, 2)
            lang == 0x1B -> Triple("sk", 2, 2)
            lang == 0x13 -> Triple("nl", 2, 2)
            lang == 0x16 -> Triple("pt", 2, 2)
            lang == 0x1D -> Triple("sv", 2, 2)
            lang == 0x14 -> Triple("nb", 2, 2)
            lang == 0x06 -> Triple("da", 2, 2)
            lang == 0x0B -> Triple("fi", 2, 2)
            lang == 0x0E -> Triple("hu", 2, 2)
            lang == 0x1F -> Triple("tr", 2, 2)
            cyr -> Triple("ru", 2, 2)
            else -> Triple("en-us", 2, 3)
        }
    }

    fun hyphenPoints(word: String, lid: Int, hyphenateCaps: Boolean): IntArray {
        val n = word.length
        if (n < 4) return IntArray(0)
        if (!hyphenateCaps && word.all { !it.isLetter() || it.isUpperCase() }) return IntArray(0)
        val (tag, minPre, minSuf) = tagFor(lid, word)
        loadHyb(tag, minPre, minSuf)?.breaks(word)?.let { return it }
        return if (word.any { it in '\u0400'..'\u04FF' }) russianFallback(word) else latinFallback(word, minPre, minSuf)
    }

    private fun isRuVowel(c: Char): Boolean = when (c) {
        'а', 'е', 'ё', 'и', 'о', 'у', 'ы', 'э', 'ю', 'я', 'є', 'і', 'ї', 'ў' -> true
        else -> false
    }

    private fun isRuSpecial(c: Char): Boolean = c == 'й' || c == 'ь' || c == 'ъ'

    private fun russianFallback(word: String): IntArray {
        val lo = word.lowercase(Locale.ROOT)
        val n = lo.length
        if (n < 4) return IntArray(0)
        val hasVowelBefore = BooleanArray(n + 1)
        val hasVowelAfter = BooleanArray(n + 1)
        for (i in 0 until n) hasVowelBefore[i + 1] = hasVowelBefore[i] || isRuVowel(lo[i])
        for (i in n - 1 downTo 0) hasVowelAfter[i] = hasVowelAfter[i + 1] || isRuVowel(lo[i])
        val out = ArrayList<Int>()
        for (k in 2..(n - 2)) {
            if (!hasVowelBefore[k] || !hasVowelAfter[k]) continue
            val prev = lo[k - 1]
            val cur = lo[k]
            if (!prev.isLetter() || !cur.isLetter()) continue
            if (isRuSpecial(cur)) continue
            if (isRuSpecial(prev)) {
                out.add(k)
                continue
            }
            if (isRuVowel(prev)) {
                if (isRuVowel(cur)) {
                    out.add(k)
                } else {
                    val next = lo.getOrNull(k + 1)
                    if (next != null && isRuVowel(next)) {
                        out.add(k)
                    } else if (next != null && isRuSpecial(next)) {
                        out.add(k)
                    }
                }
            } else {
                if (!isRuVowel(cur)) {
                    if (prev == cur) {
                        out.add(k)
                    } else if (k >= 2 && isRuVowel(lo[k - 2])) {
                        out.add(k)
                    } else if (k + 1 < n && isRuVowel(lo[k + 1])) {
                        out.add(k)
                    }
                }
            }
        }
        return out.toIntArray()
    }

    private fun isLatinVowel(c: Char): Boolean = when (c) {
        'a', 'e', 'i', 'o', 'u', 'y', 'ä', 'ö', 'ü', 'á', 'é', 'í', 'ó', 'ú', 'à', 'è', 'ì', 'ò', 'ù', 'â', 'ê', 'î', 'ô', 'û', 'ą', 'ę', 'ó' -> true
        else -> false
    }

    private fun latinFallback(word: String, minPre: Int, minSuf: Int): IntArray {
        val lo = word.lowercase(Locale.ROOT)
        val n = lo.length
        if (n < minPre + minSuf + 1) return IntArray(0)
        val hasVBefore = BooleanArray(n + 1)
        val hasVAfter = BooleanArray(n + 1)
        for (i in 0 until n) hasVBefore[i + 1] = hasVBefore[i] || isLatinVowel(lo[i])
        for (i in n - 1 downTo 0) hasVAfter[i] = hasVAfter[i + 1] || isLatinVowel(lo[i])
        val inseparable = setOf("ch", "sh", "th", "ph", "wh", "qu", "ck", "gh", "ng", "sch", "tr", "pr", "br", "cr", "dr", "fr", "gr", "pl", "bl", "cl", "fl", "gl", "st", "sp", "sc", "str")
        val out = ArrayList<Int>()
        for (k in minPre..(n - minSuf)) {
            if (!hasVBefore[k] || !hasVAfter[k]) continue
            val a = lo[k - 1]
            val b = lo[k]
            if (!a.isLetter() || !b.isLetter()) continue
            val pair = "$a$b"
            if (pair in inseparable && a != b) continue
            if (!isLatinVowel(a) && !isLatinVowel(b)) {
                out.add(k)
            } else if (isLatinVowel(a) && !isLatinVowel(b) && k + 1 < n && isLatinVowel(lo[k + 1])) {
                out.add(k)
            }
        }
        return out.toIntArray()
    }
}
