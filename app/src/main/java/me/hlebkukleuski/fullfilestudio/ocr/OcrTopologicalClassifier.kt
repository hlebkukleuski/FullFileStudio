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
 * Character recognition dictionaries and ONNX model integration adapted and modified from
 * PaddleOCR (https://github.com/PaddlePaddle/PaddleOCR, ppocr/utils/dict/),
 * Copyright (c) 2020-2026 PaddlePaddle Authors, licensed under the Apache License, Version 2.0.
 * Modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal val glyphPrototypes = ArrayList<GlyphPrototype>(1400)

internal fun segmentLineIntoGlyphRects(bitmap: Bitmap, lineBox: Rect): List<Rect> {
    val left = lineBox.left.coerceIn(0, bitmap.width - 2)
    val top = lineBox.top.coerceIn(0, bitmap.height - 2)
    val w = lineBox.width().coerceIn(2, bitmap.width - left)
    val h = lineBox.height().coerceIn(2, bitmap.height - top)

    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, left, top, w, h)

    val fgMask = binarizeCropBipolar(pixels, w, h) ?: return emptyList()
    val colProjection = IntArray(w)
    for (x in 0 until w) {
        var sum = 0
        for (y in 0 until h) {
            if (fgMask[y * w + x]) sum++
        }
        colProjection[x] = sum
    }

    val rawSpans = ArrayList<IntRange>()
    var inChar = false
    var startX = 0
    for (x in 0 until w) {
        if (colProjection[x] > 0) {
            if (!inChar) {
                inChar = true
                startX = x
            }
        } else if (inChar) {
            rawSpans.add(startX until x)
            inChar = false
        }
    }
    if (inChar) {
        rawSpans.add(startX until w)
    }

    val mergedSpans = ArrayList<IntRange>(rawSpans.size)
    var sIdx = 0
    while (sIdx < rawSpans.size) {
        val cur = rawSpans[sIdx]
        if (sIdx + 1 < rawSpans.size) {
            val next = rawSpans[sIdx + 1]
            val curW = cur.last - cur.first + 1
            val nextW = next.last - next.first + 1
            val gap = next.first - cur.last - 1
            val combinedW = next.last - cur.first + 1
            if (gap <= maxOf(2, (h * 0.13f).roundToInt()) &&
                nextW <= maxOf(3, (h * 0.24f).roundToInt()) &&
                curW >= nextW * 1.4f &&
                combinedW <= h * 1.18f
            ) {
                mergedSpans.add(cur.first..next.last)
                sIdx += 2
                continue
            }
        }
        mergedSpans.add(cur)
        sIdx++
    }

    val refinedRects = ArrayList<Rect>(mergedSpans.size + 4)
    for (span in mergedSpans) {
        val spanW = span.last - span.first + 1
        if (spanW > h * 1.65f && spanW >= 12) {
            val midStart = span.first + spanW / 3
            val midEnd = span.last - spanW / 3
            var minCol = midStart
            var minVal = Int.MAX_VALUE
            for (cx in midStart..midEnd) {
                if (colProjection[cx] < minVal) {
                    minVal = colProjection[cx]
                    minCol = cx
                }
            }
            if (minVal <= maxOf(1, h / 8) && minCol > span.first + 2 && minCol < span.last - 2) {
                buildTightGlyphRect(fgMask, w, h, span.first, minCol - 1, left, top)?.let { refinedRects.add(it) }
                buildTightGlyphRect(fgMask, w, h, minCol + 1, span.last, left, top)?.let { refinedRects.add(it) }
                continue
            }
        }
        buildTightGlyphRect(fgMask, w, h, span.first, span.last, left, top)?.let { refinedRects.add(it) }
    }

    return refinedRects
}

internal fun buildTightGlyphRect(
    fgMask: BooleanArray,
    w: Int,
    h: Int,
    x0: Int,
    x1: Int,
    globalLeft: Int,
    globalTop: Int
): Rect? {
    var minY = h
    var maxY = -1
    for (y in 0 until h) {
        val rowOff = y * w
        for (x in x0..x1) {
            if (fgMask[rowOff + x]) {
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxY < minY) return null
    return Rect(
        globalLeft + x0,
        globalTop + minY,
        globalLeft + x1 + 1,
        globalTop + maxY + 1
    )
}

internal fun binarizeCropBipolar(pixels: IntArray, w: Int, h: Int): BooleanArray? {
    val total = w * h
    if (total <= 0) return null
    val luma = IntArray(total)
    val hist = IntArray(256)
    var minL = 255
    var maxL = 0

    for (i in 0 until total) {
        val c = pixels[i]
        val y = (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        luma[i] = y
        hist[y]++
        if (y < minL) minL = y
        if (y > maxL) maxL = y
    }

    if (maxL - minL < 24) return null

    var sumAll = 0L
    for (t in 0..255) sumAll += t.toLong() * hist[t]

    var sumB = 0L
    var wB = 0
    var maxVariance = -1.0
    var threshold = (minL + maxL) / 2

    for (t in 0..255) {
        wB += hist[t]
        if (wB == 0) continue
        val wF = total - wB
        if (wF == 0) break
        sumB += t.toLong() * hist[t]
        val mB = sumB.toDouble() / wB
        val mF = (sumAll - sumB).toDouble() / wF
        val betweenVar = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
        if (betweenVar > maxVariance) {
            maxVariance = betweenVar
            threshold = t
        }
    }

    var borderBrightCount = 0
    var borderSamples = 0
    for (x in 0 until w) {
        if (luma[x] > threshold) borderBrightCount++
        if (luma[(h - 1) * w + x] > threshold) borderBrightCount++
        borderSamples += 2
    }
    for (y in 0 until h) {
        if (luma[y * w] > threshold) borderBrightCount++
        if (luma[y * w + (w - 1)] > threshold) borderBrightCount++
        borderSamples += 2
    }
    val isBackgroundBright = borderBrightCount * 2 >= borderSamples

    val mask = BooleanArray(total)
    var fgCount = 0
    for (i in 0 until total) {
        val isFg = if (isBackgroundBright) luma[i] <= threshold else luma[i] > threshold
        mask[i] = isFg
        if (isFg) fgCount++
    }

    return if (fgCount in 3..(total * 88 / 100)) mask else null
}

internal fun recognizeLineViaTopologicalClassifier(
    fullBitmap: Bitmap,
    globalBox: Rect,
    ctcHint: CtcDecodeResult?,
    lineIndex: Int,
    pageLexicon: Set<String> = emptySet()
): Pair<OcrRecognizedLine, List<OcrRecognizedChar>>? {
    val glyphRects = segmentLineIntoGlyphRects(fullBitmap, globalBox)
    if (glyphRects.isEmpty()) return null

    val heights = glyphRects.map { it.height() }.sorted()
    val medianH = heights[heights.size / 2].toFloat().coerceAtLeast(8f)
    val spaceGapThreshold = (medianH * 0.32f).coerceAtLeast(3.5f)

    val lineChars = ArrayList<OcrRecognizedChar>(glyphRects.size * 2)
    val lineSb = StringBuilder()

    val tightTop = glyphRects.minOf { it.top }.coerceAtLeast(globalBox.top)
    val tightBot = glyphRects.maxOf { it.bottom }.clampTo(tightTop + 2, globalBox.bottom)

    for (i in glyphRects.indices) {
        val gRect = glyphRects[i]
        if (i > 0) {
            val prevRect = glyphRects[i - 1]
            val gap = gRect.left - prevRect.right
            if (gap >= spaceGapThreshold) {
                val spRect = Rect(prevRect.right, tightTop, gRect.left, tightBot)
                lineChars.add(OcrRecognizedChar(" ", spRect, lineIndex))
                lineSb.append(' ')
            }
        }

        val normCenter = ((gRect.centerX() - globalBox.left).toFloat() / globalBox.width().coerceAtLeast(1))
        val ctcMatchedToken = ctcHint?.tokens?.firstOrNull { tok ->
            val tokStartNorm = tok.startStep.toFloat() / ctcHint.totalTimesteps.coerceAtLeast(1)
            val tokEndNorm = (tok.endStep + 1).toFloat() / ctcHint.totalTimesteps.coerceAtLeast(1)
            val cp = if (tok.text.isNotEmpty()) tok.text.codePointAt(0) else 0
            val isSupportedAlphabet = cp in 0x21..0x7E || cp in 0x00A0..0x052F
            tok.confidence >= 0.84f &&
                    isSupportedAlphabet &&
                    normCenter in (tokStartNorm - 0.06f)..(tokEndNorm + 0.06f)
        }

        val recognizedGrapheme = ctcMatchedToken?.text
            ?: classifySingleGlyphRect(fullBitmap, gRect, isTallInLine = gRect.height() >= medianH * 0.92f)

        val expandedRect = Rect(gRect.left, tightTop, gRect.right, tightBot)
        lineChars.add(OcrRecognizedChar(recognizedGrapheme, expandedRect, lineIndex))
        lineSb.append(recognizedGrapheme)
    }

    val rawLineStr = lineSb.toString().trim()
    if (rawLineStr.isEmpty()) return null
    val tightLineBox = Rect(
        glyphRects.first().left.coerceAtLeast(globalBox.left),
        tightTop,
        glyphRects.last().right.coerceAtMost(globalBox.right),
        tightBot
    )
    val snappedLineStr = snapLineToPageLexicon(rawLineStr, pageLexicon)
    if (snappedLineStr != rawLineStr && snappedLineStr.length == rawLineStr.length) {
        val updatedChars = ArrayList<OcrRecognizedChar>(lineChars.size)
        val newGraphemes = OcrLexiconAndTypography.splitIntoGraphemes(snappedLineStr)
        if (newGraphemes.size == lineChars.size) {
            for (k in lineChars.indices) {
                updatedChars.add(lineChars[k].copy(text = newGraphemes[k]))
            }
            return OcrRecognizedLine(snappedLineStr, tightLineBox) to updatedChars
        }
    }
    return OcrRecognizedLine(snappedLineStr, tightLineBox) to lineChars
}

internal fun classifySingleGlyphRect(
    bitmap: Bitmap,
    glyphRect: Rect,
    isTallInLine: Boolean
): String {
    val w = glyphRect.width().coerceAtLeast(1)
    val h = glyphRect.height().coerceAtLeast(1)

    if (h <= 5 && w <= 5) return "."
    if (h <= 5 && w >= h * 2.2f) return "-"

    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, glyphRect.left, glyphRect.top, w, h)
    val fgMask = binarizeCropBipolar(pixels, w, h) ?: return "?"

    val descriptor = extractGlyphDescriptorFromMask(fgMask, w, h, "", isTallInLine, false)
    var bestScore = Float.NEGATIVE_INFINITY
    var bestGrapheme = "?"

    for (proto in glyphPrototypes) {
        val aspectDiff = abs(proto.aspectRatio - descriptor.aspectRatio)
        if (aspectDiff > 0.65f) continue

        var matchingBits = 0
        for (k in 0 until 4) {
            val xorBits = proto.bits16x16[k] xor descriptor.bits16x16[k]
            matchingBits += 64 - java.lang.Long.bitCount(xorBits)
        }
        val bitSim = matchingBits / 256.0f

        var dot = 0f
        for (z in 0 until 16) {
            dot += proto.zones4x4[z] * descriptor.zones4x4[z]
        }
        val cosineSim = dot / (proto.normL2 * descriptor.normL2).coerceAtLeast(1e-5f)

        val holePenalty = abs(proto.eulerHoles - descriptor.eulerHoles) * 0.08f
        val caseBonus = if (proto.isUpper == isTallInLine) 0.03f else 0f

        val totalScore = cosineSim * 0.58f + bitSim * 0.42f - aspectDiff * 0.12f - holePenalty + caseBonus
        if (totalScore > bestScore) {
            bestScore = totalScore
            bestGrapheme = proto.grapheme
        }
    }

    return bestGrapheme
}

/**
 * Builds the clean-room 16x16 topological prototype bank using standard modern alphabets
 * (Russian, Ukrainian, Belarusian, English, German, Spanish, French, Polish, digits, and punctuation)
 * without archaic Slavonic or Mathematical Fraktur symbols that could cause false homoglyph matches.
 */
internal fun ensureTopologicalGlyphBankLocked() {
    if (glyphPrototypes.isNotEmpty()) return
    val alphabet = buildString {
        append("АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ")
        append("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
        append("ІЇЄҐіїєґЎў")
        append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        append("abcdefghijklmnopqrstuvwxyz")
        append("ÄÖÜäöüßĄĆĘŁŃÓŚŹŻąćęłńóśźżÉÈÊËéèêëÁÍÓÚÑáíóúñ")
        append("0123456789")
        append("!?%&()[]{}+=*/:;.,«»№€₽$£¥")
    }
    val graphemes = OcrLexiconAndTypography.splitIntoGraphemes(alphabet)

    val typefaces = listOf(
        Typeface.SANS_SERIF to 1.0f,
        Typeface.SERIF to 1.0f,
        Typeface.DEFAULT_BOLD to 1.0f,
        Typeface.DEFAULT_BOLD to 0.74f
    )

    val scratchBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    val scratchCanvas = Canvas(scratchBmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
    }
    val pixels = IntArray(32 * 32)
    val mask = BooleanArray(32 * 32)

    for ((tf, scaleX) in typefaces) {
        paint.typeface = tf
        paint.textScaleX = scaleX
        for (g in graphemes) {
            if (!paint.hasGlyph(g)) continue
            scratchBmp.eraseColor(Color.BLACK)
            scratchCanvas.drawText(g, 4f, 26f, paint)
            scratchBmp.getPixels(pixels, 0, 32, 0, 0, 32, 32)

            var minX = 32
            var maxX = -1
            var minY = 32
            var maxY = -1
            for (y in 0 until 32) {
                val rOff = y * 32
                for (x in 0 until 32) {
                    val isInk = (pixels[rOff + x] and 0xFF) > 96
                    mask[rOff + x] = isInk
                    if (isInk) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX >= minX && maxY >= minY) {
                val cw = maxX - minX + 1
                val chH = maxY - minY + 1
                val croppedMask = BooleanArray(cw * chH)
                for (y in 0 until chH) {
                    for (x in 0 until cw) {
                        croppedMask[y * cw + x] = mask[(minY + y) * 32 + (minX + x)]
                    }
                }
                val cp = g.codePointAt(0)
                glyphPrototypes.add(
                    extractGlyphDescriptorFromMask(
                        mask = croppedMask,
                        w = cw,
                        h = chH,
                        grapheme = g,
                        isUpper = Character.isUpperCase(cp),
                        isDigitOrPunct = !Character.isLetter(cp)
                    )
                )
            }
        }
    }
    scratchBmp.recycle()
}

internal fun extractGlyphDescriptorFromMask(
    mask: BooleanArray,
    w: Int,
    h: Int,
    grapheme: String,
    isUpper: Boolean,
    isDigitOrPunct: Boolean
): GlyphPrototype {
    val bits16x16 = LongArray(4)
    val zones4x4 = FloatArray(16)

    for (gy in 0 until 16) {
        val srcY = (gy * h / 16).coerceIn(0, h - 1)
        for (gx in 0 until 16) {
            val srcX = (gx * w / 16).coerceIn(0, w - 1)
            if (mask[srcY * w + srcX]) {
                val bitIndex = gy * 16 + gx
                bits16x16[bitIndex ushr 6] = bits16x16[bitIndex ushr 6] or (1L shl (bitIndex and 63))
                val zoneIdx = (gy / 4) * 4 + (gx / 4)
                zones4x4[zoneIdx] += 1f / 16f
            }
        }
    }

    var sumSq = 0f
    for (z in zones4x4) sumSq += z * z
    val normL2 = sqrt(sumSq).coerceAtLeast(1e-4f)

    val bgVisited = BooleanArray(256)
    val q = IntArray(256)
    fun isInk16(x: Int, y: Int): Boolean {
        val idx = y * 16 + x
        return ((bits16x16[idx ushr 6] ushr (idx and 63)) and 1L) != 0L
    }

    var holes = 0
    var holeYAccum = 0f
    for (y in 0 until 16) {
        for (x in 0 until 16) {
            val startIdx = y * 16 + x
            if (!isInk16(x, y) && !bgVisited[startIdx]) {
                var touchesBorder = false
                var qH = 0
                var qT = 0
                var ySum = 0
                q[qT++] = startIdx
                bgVisited[startIdx] = true
                while (qH < qT) {
                    val cur = q[qH++]
                    val cy = cur / 16
                    val cx = cur % 16
                    ySum += cy
                    if (cx == 0 || cx == 15 || cy == 0 || cy == 15) touchesBorder = true
                    val neighbors = intArrayOf(
                        if (cy > 0) (cy - 1) * 16 + cx else -1,
                        if (cy < 15) (cy + 1) * 16 + cx else -1,
                        if (cx > 0) cy * 16 + (cx - 1) else -1,
                        if (cx < 15) cy * 16 + (cx + 1) else -1
                    )
                    for (nb in neighbors) {
                        if (nb >= 0 && !bgVisited[nb] && !isInk16(nb % 16, nb / 16)) {
                            bgVisited[nb] = true
                            q[qT++] = nb
                        }
                    }
                }
                if (!touchesBorder && qT >= 2) {
                    holes++
                    holeYAccum += (ySum.toFloat() / qT) / 16f
                }
            }
        }
    }

    return GlyphPrototype(
        grapheme = grapheme,
        isUpper = isUpper,
        isDigitOrPunct = isDigitOrPunct,
        aspectRatio = w.toFloat() / h.coerceAtLeast(1).toFloat(),
        eulerHoles = holes,
        holeCenterYNorm = if (holes > 0) holeYAccum / holes else 0.5f,
        bits16x16 = bits16x16,
        zones4x4 = zones4x4,
        normL2 = normL2
    )
}

