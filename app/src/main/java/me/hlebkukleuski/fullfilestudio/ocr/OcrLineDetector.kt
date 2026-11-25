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
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun postProcessDbNet(
    probPlane: Array<FloatArray>,
    detW: Int,
    detH: Int,
    origW: Int,
    origH: Int
): List<Rect> {
    val boxes = ArrayList<Rect>()
    try {
        val scaleX = origW.toFloat() / detW.coerceAtLeast(1)
        val scaleY = origH.toFloat() / detH.coerceAtLeast(1)
        val visited = BooleanArray(detH * detW)
        val queue = IntArray(detH * detW)
        val thresh = 0.26f

        for (r in 0 until detH) {
            val rowOff = r * detW
            val probRow = probPlane[r]
            for (c in 0 until detW) {
                val flatIdx = rowOff + c
                if (!visited[flatIdx] && probRow[c] > thresh) {
                    var minR = r
                    var maxR = r
                    var minC = c
                    var maxC = c
                    var probSum = 0f
                    var pixelCount = 0
                    var perimeterCount = 0

                    var qHead = 0
                    var qTail = 0
                    queue[qTail++] = (r shl 16) or c
                    visited[flatIdx] = true

                    while (qHead < qTail) {
                        val curr = queue[qHead++]
                        val cr = curr ushr 16
                        val cc = curr and 0xFFFF
                        probSum += probPlane[cr][cc]
                        pixelCount++

                        if (cr < minR) minR = cr
                        if (cr > maxR) maxR = cr
                        if (cc < minC) minC = cc
                        if (cc > maxC) maxC = cc

                        var isBoundary = false
                        if (cr > 0) {
                            val nr = cr - 1
                            val nIdx = nr * detW + cc
                            if (probPlane[nr][cc] > thresh) {
                                if (!visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue[qTail++] = (nr shl 16) or cc
                                }
                            } else isBoundary = true
                        } else isBoundary = true

                        if (cr + 1 < detH) {
                            val nr = cr + 1
                            val nIdx = nr * detW + cc
                            if (probPlane[nr][cc] > thresh) {
                                if (!visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue[qTail++] = (nr shl 16) or cc
                                }
                            } else isBoundary = true
                        } else isBoundary = true

                        if (cc > 0) {
                            val nc = cc - 1
                            val nIdx = cr * detW + nc
                            if (probPlane[cr][nc] > thresh) {
                                if (!visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue[qTail++] = (cr shl 16) or nc
                                }
                            } else isBoundary = true
                        } else isBoundary = true

                        if (cc + 1 < detW) {
                            val nc = cc + 1
                            val nIdx = cr * detW + nc
                            if (probPlane[cr][nc] > thresh) {
                                if (!visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue[qTail++] = (cr shl 16) or nc
                                }
                            } else isBoundary = true
                        } else isBoundary = true

                        if (isBoundary) perimeterCount++
                    }

                    val boxW = maxC - minC + 1
                    val boxH = maxR - minR + 1
                    val meanProb = probSum / pixelCount.coerceAtLeast(1)
                    if (boxW >= 2 && boxH >= 2 && meanProb >= 0.32f) {
                        val rectPerimeter = 2f * (boxW + boxH)
                        val effPerimeter = maxOf(perimeterCount.toFloat(), rectPerimeter * 0.75f).coerceAtLeast(4f)
                        val unclipDist = (pixelCount.toFloat() * 1.65f) / effPerimeter
                        val unclipY = unclipDist.roundToInt().coerceIn(1, maxOf(6, (boxH * 0.65f).roundToInt()))
                        val unclipX = (unclipDist * 1.25f).roundToInt().coerceIn(2, maxOf(10, (boxH * 0.95f).roundToInt()))

                        val unclipLeft = ((minC - unclipX) * scaleX).roundToInt().coerceIn(0, origW - 2)
                        val unclipTop = ((minR - unclipY) * scaleY).roundToInt().coerceIn(0, origH - 2)
                        val unclipRight = ((maxC + 1 + unclipX) * scaleX).roundToInt().coerceIn(unclipLeft + 1, origW)
                        val unclipBottom = ((maxR + 1 + unclipY) * scaleY).roundToInt().coerceIn(unclipTop + 1, origH)

                        boxes.add(Rect(unclipLeft, unclipTop, unclipRight, unclipBottom))
                    }
                }
            }
        }
    } catch (_: Throwable) {
    }
    return sortLineBoxesReadingOrder(boxes, origW)
}

/**
 * Splits a horizontal line band into separate column/phrase segments whenever there is a wide
 * vertical gap without ink edges (`gap >= maxOf(lineGridH * 1.55f, 9)`), preventing adjacent
 * table cells or multi-column text from merging into a single full-width selection bar.
 */
internal fun splitRowIntoHorizontalSegments(
    edgeMask: BooleanArray,
    gridW: Int,
    minX: Int,
    maxX: Int,
    subMinY: Int,
    subMaxY: Int,
    step: Int,
    origW: Int,
    origH: Int,
    outBoxes: MutableList<Rect>
) {
    val lineGridH = (subMaxY - subMinY + 1).coerceAtLeast(1)
    val maxIntraPhraseGap = maxOf((lineGridH * 1.55f).roundToInt(), 9)

    var segStartX = -1
    var lastInkX = -1
    var segEdgePixels = 0

    fun flushSegment(x0: Int, x1: Int, pixelsCount: Int) {
        if (x1 < x0 || pixelsCount < 3) return
        var tightMinY = subMaxY
        var tightMaxY = subMinY
        var tightMinX = x1
        var tightMaxX = x0
        for (gy in subMinY..subMaxY) {
            val rOff = gy * gridW
            for (gx in x0..x1) {
                if (edgeMask[rOff + gx]) {
                    if (gy < tightMinY) tightMinY = gy
                    if (gy > tightMaxY) tightMaxY = gy
                    if (gx < tightMinX) tightMinX = gx
                    if (gx > tightMaxX) tightMaxX = gx
                }
            }
        }
        if (tightMaxY < tightMinY || tightMaxX < tightMinX) return
        val bw = (tightMaxX - tightMinX + 1) * step
        val bh = (tightMaxY - tightMinY + 1) * step
        if (bw >= 3 && bh >= 5) {
            val padX = (bh * 0.10f).roundToInt().coerceIn(1, 6)
            val padY = (bh * 0.08f).roundToInt().coerceIn(1, 4)
            val left = (tightMinX * step - padX).coerceIn(0, origW - 2)
            val top = (tightMinY * step - padY).coerceIn(0, origH - 2)
            val right = ((tightMaxX + 1) * step + padX).coerceIn(left + 1, origW)
            val bottom = ((tightMaxY + 1) * step + padY).coerceIn(top + 1, origH)
            outBoxes.add(Rect(left, top, right, bottom))
        }
    }

    for (gx in minX..maxX) {
        var colInk = 0
        for (gy in subMinY..subMaxY) {
            if (edgeMask[gy * gridW + gx]) colInk++
        }
        if (colInk > 0) {
            if (segStartX < 0) {
                segStartX = gx
                segEdgePixels = colInk
            } else if (gx - lastInkX > maxIntraPhraseGap) {
                flushSegment(segStartX, lastInkX, segEdgePixels)
                segStartX = gx
                segEdgePixels = colInk
            } else {
                segEdgePixels += colInk
            }
            lastInkX = gx
        }
    }
    if (segStartX >= 0 && lastInkX >= segStartX) {
        flushSegment(segStartX, lastInkX, segEdgePixels)
    }
}

/**
 * Splits any multi-line bounding box into individual horizontal text line boxes using
 * horizontal edge projection valleys, and then splits each line horizontally across wide
 * column gaps.
 */
internal fun splitTallBoxIntoHorizontalLines(
    edgeMask: BooleanArray,
    gridW: Int,
    gridH: Int,
    minX: Int,
    maxX: Int,
    minY: Int,
    maxY: Int,
    step: Int,
    origW: Int,
    origH: Int,
    outBoxes: MutableList<Rect>
) {
    val boxGridH = maxY - minY + 1
    val boxGridW = maxX - minX + 1
    if (boxGridH <= 0 || boxGridW <= 0) return

    val rowCounts = IntArray(boxGridH)
    var maxRowCount = 0
    for (ry in 0 until boxGridH) {
        val gy = minY + ry
        val rowOff = gy * gridW
        var c = 0
        for (gx in minX..maxX) {
            if (edgeMask[rowOff + gx]) c++
        }
        rowCounts[ry] = c
        if (c > maxRowCount) maxRowCount = c
    }

    if (maxRowCount == 0) return
    val valleyThreshold = maxOf(1, (maxRowCount * 0.14f).roundToInt())

    val lineRanges = ArrayList<IntRange>()
    var inLine = false
    var lineStart = 0
    for (ry in 0 until boxGridH) {
        if (rowCounts[ry] > valleyThreshold) {
            if (!inLine) {
                inLine = true
                lineStart = ry
            }
        } else if (inLine) {
            if (ry - lineStart >= 3) {
                lineRanges.add(lineStart until ry)
            }
            inLine = false
        }
    }
    if (inLine && boxGridH - lineStart >= 3) {
        lineRanges.add(lineStart until boxGridH)
    }

    if (lineRanges.isEmpty()) {
        lineRanges.add(0 until boxGridH)
    }

    val tallestRange = lineRanges.maxOf { it.last - it.first + 1 }
    val mergedRanges = ArrayList<IntRange>(lineRanges.size)
    var rangeIdx = 0
    while (rangeIdx < lineRanges.size) {
        val range = lineRanges[rangeIdx]
        val isShortBand = (range.last - range.first + 1) * 100 < tallestRange * 45
        if (isShortBand) {
            val prev = mergedRanges.lastOrNull()
            val next = lineRanges.getOrNull(rangeIdx + 1)
            val gapToPrev = if (prev != null) range.first - prev.last else Int.MAX_VALUE
            val gapToNext = if (next != null) next.first - range.last else Int.MAX_VALUE
            if (next != null && gapToNext < gapToPrev) {
                lineRanges[rangeIdx + 1] = range.first..next.last
                rangeIdx++
                continue
            }
            if (prev != null) {
                mergedRanges[mergedRanges.lastIndex] = prev.first..range.last
                rangeIdx++
                continue
            }
        }
        mergedRanges.add(range)
        rangeIdx++
    }

    for (range in mergedRanges) {
        val subMinY = minY + range.first
        val subMaxY = minY + range.last
        splitRowIntoHorizontalSegments(
            edgeMask = edgeMask,
            gridW = gridW,
            minX = minX,
            maxX = maxX,
            subMinY = subMinY,
            subMaxY = subMaxY,
            step = step,
            origW = origW,
            origH = origH,
            outBoxes = outBoxes
        )
    }
}

internal fun detectBipolarHighContrastLineBoxes(bitmap: Bitmap): List<Rect> {
    val origW = bitmap.width
    val origH = bitmap.height
    val step = if (maxOf(origW, origH) > 1200) 2 else 1
    val gridW = origW / step
    val gridH = origH / step
    if (gridW < 16 || gridH < 16) return emptyList()

    val pixels = IntArray(origW * origH)
    bitmap.getPixels(pixels, 0, origW, 0, 0, origW, origH)

    val luma = IntArray(gridW * gridH)
    for (gy in 0 until gridH) {
        val srcRow = (gy * step) * origW
        val dstRow = gy * gridW
        for (gx in 0 until gridW) {
            val c = pixels[srcRow + gx * step]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            luma[dstRow + gx] = (r * 77 + g * 150 + b * 29) shr 8
        }
    }

    val edgeMask = BooleanArray(gridW * gridH)
    val contrastThreshold = 32
    for (gy in 2 until gridH - 2) {
        val row = gy * gridW
        for (gx in 2 until gridW - 2) {
            val idx = row + gx
            val center = luma[idx]
            val diffH = maxOf(abs(center - luma[idx - 1]), abs(center - luma[idx + 1]))
            val diffH2 = maxOf(abs(center - luma[idx - 2]), abs(center - luma[idx + 2]))
            val diffV = maxOf(abs(center - luma[idx - gridW]), abs(center - luma[idx + gridW]))
            if (maxOf(diffH, diffH2) >= contrastThreshold || diffV >= contrastThreshold + 6) {
                edgeMask[idx] = true
            }
        }
    }

    /* Suppress long vertical and horizontal border lines (table frames, page card borders) */
    val minVertLineRun = (gridH * 30) / 100
    for (gx in 1 until gridW - 1) {
        var runStart = -1
        for (gy in 0 until gridH) {
            if (edgeMask[gy * gridW + gx]) {
                if (runStart < 0) runStart = gy
            } else if (runStart >= 0) {
                if (gy - runStart >= minVertLineRun) {
                    for (clearY in runStart until gy) edgeMask[clearY * gridW + gx] = false
                }
                runStart = -1
            }
        }
        if (runStart >= 0 && gridH - runStart >= minVertLineRun) {
            for (clearY in runStart until gridH) edgeMask[clearY * gridW + gx] = false
        }
    }

    val minHorizLineRun = (gridW * 32) / 100
    for (gy in 1 until gridH - 1) {
        val rowOff = gy * gridW
        var runStart = -1
        for (gx in 0 until gridW) {
            if (edgeMask[rowOff + gx]) {
                if (runStart < 0) runStart = gx
            } else if (runStart >= 0) {
                if (gx - runStart >= minHorizLineRun) {
                    for (clearX in runStart until gx) edgeMask[rowOff + clearX] = false
                }
                runStart = -1
            }
        }
        if (runStart >= 0 && gridW - runStart >= minHorizLineRun) {
            for (clearX in runStart until gridW) edgeMask[rowOff + clearX] = false
        }
    }

    /* Horizontal-only morphological dilation: merges intra-word characters without bridging adjacent lines vertically */
    val dilated = BooleanArray(gridW * gridH)
    val radiusX = (gridW / 120).coerceIn(2, 6)
    for (gy in 1 until gridH - 1) {
        val row = gy * gridW
        var lastEdgeX = -1000
        for (gx in 0 until gridW) {
            if (edgeMask[row + gx]) lastEdgeX = gx
            if (gx - lastEdgeX <= radiusX) dilated[row + gx] = true
        }
        var nextEdgeX = 100000
        for (gx in gridW - 1 downTo 0) {
            if (edgeMask[row + gx]) nextEdgeX = gx
            if (nextEdgeX - gx <= radiusX) dilated[row + gx] = true
        }
    }

    val visited = BooleanArray(gridW * gridH)
    val queue = IntArray(gridW * gridH)
    val rawBoxes = ArrayList<Rect>()

    for (gy in 1 until gridH - 1) {
        val row = gy * gridW
        for (gx in 1 until gridW - 1) {
            val startIdx = row + gx
            if (dilated[startIdx] && !visited[startIdx]) {
                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var count = 0

                var qHead = 0
                var qTail = 0
                queue[qTail++] = (gy shl 16) or gx
                visited[startIdx] = true

                while (qHead < qTail) {
                    val curr = queue[qHead++]
                    val cy = curr ushr 16
                    val cx = curr and 0xFFFF
                    count++

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    for (ny in maxOf(0, cy - 1)..minOf(gridH - 1, cy + 1)) {
                        val nRow = ny * gridW
                        for (nx in maxOf(0, cx - 1)..minOf(gridW - 1, cx + 1)) {
                            val nIdx = nRow + nx
                            if (dilated[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[qTail++] = (ny shl 16) or nx
                            }
                        }
                    }
                }

                val bw = (maxX - minX + 1) * step
                val bh = (maxY - minY + 1) * step
                if (bw >= 3 && bh >= 5 && count >= 4) {
                    if (bh >= 20) {
                        splitTallBoxIntoHorizontalLines(
                            edgeMask = edgeMask,
                            gridW = gridW,
                            gridH = gridH,
                            minX = minX,
                            maxX = maxX,
                            minY = minY,
                            maxY = maxY,
                            step = step,
                            origW = origW,
                            origH = origH,
                            outBoxes = rawBoxes
                        )
                    } else {
                        splitRowIntoHorizontalSegments(
                            edgeMask = edgeMask,
                            gridW = gridW,
                            minX = minX,
                            maxX = maxX,
                            subMinY = minY,
                            subMaxY = maxY,
                            step = step,
                            origW = origW,
                            origH = origH,
                            outBoxes = rawBoxes
                        )
                    }
                }
            }
        }
    }

    return sortLineBoxesReadingOrder(rawBoxes, origW)
}

internal fun sortLineBoxesReadingOrder(boxes: List<Rect>, pageWidth: Int = 1920): List<Rect> {
    if (boxes.size <= 1) return boxes
    val heights = boxes.map { it.height() }.sorted()
    val medianH = heights[heights.size / 2].toFloat().coerceAtLeast(12f)
    val lineBin = (medianH * 0.58f).coerceAtLeast(8f)

    val ySorted = boxes.sortedWith(compareBy({ (it.centerY() / lineBin).toInt() }, { it.left }))
    val mergedLines = ArrayList<Rect>(ySorted.size)
    for (b in ySorted) {
        val prev = mergedLines.lastOrNull()
        if (prev != null) {
            val verticalOverlap = minOf(prev.bottom, b.bottom) - maxOf(prev.top, b.top)
            val minH = minOf(prev.height(), b.height()).coerceAtLeast(1)
            val horizGap = b.left - prev.right
            val maxMergeGap = (maxOf(prev.height(), b.height()) * 1.05f).roundToInt()
            if (verticalOverlap >= minH * 0.52f && horizGap in -10..maxMergeGap) {
                mergedLines[mergedLines.lastIndex] = Rect(
                    minOf(prev.left, b.left),
                    minOf(prev.top, b.top),
                    maxOf(prev.right, b.right),
                    maxOf(prev.bottom, b.bottom)
                )
                continue
            }
        }
        mergedLines.add(Rect(b))
    }

    if (mergedLines.size <= 2) return mergedLines

    class SpatialTextBlock(firstLine: Rect) {
        val bounds = Rect(firstLine)
        val lines = ArrayList<Rect>().apply { add(firstLine) }
    }

    val blocks = ArrayList<SpatialTextBlock>()
    val fullWidthThreshold = (pageWidth * 0.52f).roundToInt()

    for (line in mergedLines) {
        val isFullWidthBanner = line.width() >= fullWidthThreshold
        var bestBlock: SpatialTextBlock? = null
        var bestVertGap = Int.MAX_VALUE

        if (!isFullWidthBanner) {
            for (blk in blocks) {
                if (blk.bounds.width() >= fullWidthThreshold) continue
                val vGap = line.top - blk.bounds.bottom
                val maxAllowedVGap = (maxOf(line.height(), blk.lines.last().height()) * 2.2f).roundToInt().coerceAtLeast(18)
                if (vGap in -12..maxAllowedVGap) {
                    val overlapX = minOf(blk.bounds.right, line.right) - maxOf(blk.bounds.left, line.left)
                    val minW = minOf(blk.bounds.width(), line.width()).coerceAtLeast(1)
                    val centerAligned = abs(blk.bounds.centerX() - line.centerX()) <= maxOf(blk.bounds.width(), line.width()) * 0.35f
                    val leftAligned = abs(blk.bounds.left - line.left) <= medianH * 2.0f
                    if ((overlapX >= minW * 0.30f || centerAligned || leftAligned) && vGap < bestVertGap) {
                        bestVertGap = vGap
                        bestBlock = blk
                    }
                }
            }
        }

        if (bestBlock != null) {
            bestBlock.lines.add(line)
            bestBlock.bounds.union(line)
        } else {
            blocks.add(SpatialTextBlock(line))
        }
    }

    val blockRowBin = (medianH * 2.4f).coerceAtLeast(24f)
    val orderedBlocks = sortIntoVisualRows(blocks, { it.bounds }) { anchor, candidate ->
        val vOverlap = minOf(anchor.bottom, candidate.bottom) - maxOf(anchor.top, candidate.top)
        val minBlockH = minOf(anchor.height(), candidate.height()).coerceAtLeast(1)
        vOverlap >= minBlockH * 0.35f || abs(anchor.top - candidate.top) <= blockRowBin
    }

    val orderedOut = ArrayList<Rect>(mergedLines.size)
    for (blk in orderedBlocks) {
        blk.lines.sortWith(compareBy({ (it.centerY() / lineBin).toInt() }, { it.left }))
        orderedOut.addAll(blk.lines)
    }
    return orderedOut
}

/**
 * Aligns detected visual text line boxes with [pageTextHint] across all zoom levels (`zoom >= 0.5f`).
 * Groups horizontally adjacent boxes on the same visual line into `VisualRowGroup`s, filters out
 * tiny noise boxes, and distributes words proportionally across same-row column boxes so selection
 * zones never drift onto the wrong line or empty whitespace.
 */
