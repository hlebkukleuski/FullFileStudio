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

package me.hlebkukleuski.fullfilestudio.feature.office

import android.graphics.RectF
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

data class CellRange(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int
)

/**
 * Maps screen selection bounds and search highlights on a rendered spreadsheet tile to row and column indices,
 * supporting both uniform and custom per-column widths (`<col width="...">`, `COLINFO 0x007D`),
 * pinned headers, zoom scale, and 2D merged cell regions (`colSpan` + `rowSpan`).
 */
object SpreadsheetCoordinateUtils {

    const val BASE_HEADER_WIDTH = 84f
    const val BASE_HEADER_HEIGHT = 56f
    const val BASE_CELL_WIDTH = 170f
    const val BASE_CELL_HEIGHT = 48f

    fun columnIndexToExcelLetters(colIndex: Int): String {
        if (colIndex < 0) return ""
        var n = colIndex + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    fun columnIndexFromCellRef(cellRef: String): Int {
        if (cellRef.isBlank()) return -1
        var col = 0
        var foundLetters = false
        for (ch in cellRef.uppercase(Locale.ROOT)) {
            if (ch in 'A'..'Z') {
                foundLetters = true
                col = col * 26 + (ch - 'A' + 1)
            } else if (ch.isDigit() || ch == '$') {
                break
            }
        }
        return if (foundLetters) col - 1 else -1
    }

    /**
     * Converts Excel character-width units (`<col width="...">` or BIFF8 `COLINFO` 1/256th char units)
     * into unzoomed pixel widths on the canvas, clamped to a readable range.
     */
    fun excelCharWidthToPixels(charWidth: Float): Float {
        if (!charWidth.isFinite() || charWidth <= 0f) return BASE_CELL_WIDTH
        return (charWidth * 18.5f + 16f).coerceIn(64f, 680f)
    }

    /**
     * Resolves the total number of columns on the active sheet from [docWidth] and optional [customColWidthsPx],
     * ensuring trailing columns beyond [customColWidthsPx] are never lost.
     */
    fun resolveColumnCount(
        docWidth: Float,
        customColWidthsPx: FloatArray?,
        minRequiredCols: Int = 1
    ): Int {
        if (customColWidthsPx != null && customColWidthsPx.isNotEmpty()) {
            var customSum = 0f
            for (w in customColWidthsPx) {
                val cleanW = if (w.isFinite() && w > 0f) w else BASE_CELL_WIDTH
                customSum += cleanW.coerceIn(60f, 680f)
            }
            val extraWidth = (docWidth - BASE_HEADER_WIDTH - customSum).coerceAtLeast(0f)
            val extraCols = (extraWidth / BASE_CELL_WIDTH).roundToInt().coerceAtLeast(0)
            return maxOf(customColWidthsPx.size + extraCols, minRequiredCols, 1)
        }
        val fromDocW = if (docWidth > BASE_HEADER_WIDTH) {
            ((docWidth - BASE_HEADER_WIDTH) / BASE_CELL_WIDTH).roundToInt().coerceAtLeast(1)
        } else {
            1
        }
        return maxOf(fromDocW, minRequiredCols, 1)
    }

    /**
     * Builds cumulative X offsets (`size == colCount + 1`) for a sheet's columns at the given [zoom],
     * stretching columns proportionally if the total table width is narrower than [viewportWidth].
     */
    fun computeScaledColumnOffsets(
        colCount: Int,
        customColWidthsPx: FloatArray?,
        viewportWidth: Float,
        zoom: Float
    ): FloatArray {
        val safeCols = colCount.coerceAtLeast(1)
        val unzoomedAvailW = (viewportWidth - BASE_HEADER_WIDTH).coerceAtLeast(BASE_CELL_WIDTH)
        val rawUnzoomed = FloatArray(safeCols) { c ->
            val w = if (customColWidthsPx != null && c < customColWidthsPx.size && customColWidthsPx[c] > 0f) {
                customColWidthsPx[c]
            } else {
                BASE_CELL_WIDTH
            }
            w.coerceIn(60f, 680f)
        }
        val rawSum = rawUnzoomed.sum().coerceAtLeast(1f)
        val stretchFactor = if (viewportWidth > BASE_HEADER_WIDTH && rawSum < unzoomedAvailW) {
            unzoomedAvailW / rawSum
        } else {
            1f
        }

        val offsets = FloatArray(safeCols + 1)
        var acc = 0f
        for (c in 0 until safeCols) {
            offsets[c] = acc
            acc += rawUnzoomed[c] * stretchFactor * zoom
        }
        offsets[safeCols] = acc
        return offsets
    }

    fun findColumnAtContentX(colOffsets: FloatArray, contentX: Float): Int {
        val colCount = (colOffsets.size - 1).coerceAtLeast(1)
        if (contentX <= 0f) return 0
        if (contentX >= colOffsets[colCount]) return colCount - 1
        var lo = 0
        var hi = colCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val left = colOffsets[mid]
            val right = colOffsets[mid + 1]
            if (contentX >= left && contentX < right) return mid
            if (contentX < left) hi = mid - 1 else lo = mid + 1
        }
        return lo.coerceIn(0, colCount - 1)
    }

    /**
     * Computes the visible rectangle of a cell or merged region `(startRow..endRow, startCol..endCol)`
     * in bitmap coordinates, accounting for pinned headers and current scroll offset.
     */
    fun computeCellRectOnBitmap(
        startRow: Int,
        startCol: Int,
        endRow: Int = startRow,
        endCol: Int = startCol,
        colOffsets: FloatArray,
        zoom: Float,
        effScrollX: Float,
        effScrollY: Float
    ): RectF {
        val colCount = (colOffsets.size - 1).coerceAtLeast(1)
        val c0 = startCol.coerceIn(0, colCount - 1)
        val c1 = endCol.coerceIn(c0, colCount - 1)
        val r0 = startRow.coerceAtLeast(0)
        val r1 = endRow.coerceAtLeast(r0)

        val headerW = BASE_HEADER_WIDTH * zoom
        val headerH = BASE_HEADER_HEIGHT * zoom
        val cellH = BASE_CELL_HEIGHT * zoom

        val left = headerW + colOffsets[c0] - effScrollX
        val right = headerW + colOffsets[c1 + 1] - effScrollX
        val top = headerH + r0 * cellH - effScrollY
        val bottom = headerH + (r1 + 1) * cellH - effScrollY
        return RectF(left, top, right, bottom)
    }

    fun calculateCellRange(
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        scale: Float,
        fontScaleMultiplier: Float,
        offsetX: Float,
        offsetY: Float,
        viewportWidth: Float = 0f,
        docWidth: Float = 0f,
        viewportHeight: Float = 0f,
        docHeight: Float = 0f,
        customColWidthsPx: FloatArray? = null
    ): CellRange {
        val zoom = (scale * fontScaleMultiplier).coerceIn(0.5f, 4f)
        val headerWidth = BASE_HEADER_WIDTH * zoom
        val headerHeight = BASE_HEADER_HEIGHT * zoom

        val colCount = resolveColumnCount(
            docWidth = docWidth,
            customColWidthsPx = customColWidthsPx,
            minRequiredCols = 1
        )
        val rowCount = if (docHeight > BASE_HEADER_HEIGHT) {
            ((docHeight - BASE_HEADER_HEIGHT) / BASE_CELL_HEIGHT).roundToInt().coerceAtLeast(1)
        } else {
            1
        }

        val colOffsets = computeScaledColumnOffsets(
            colCount = colCount,
            customColWidthsPx = customColWidthsPx,
            viewportWidth = if (viewportWidth > BASE_HEADER_WIDTH && (docWidth > BASE_HEADER_WIDTH || customColWidthsPx != null)) viewportWidth else 0f,
            zoom = zoom
        )
        val cellHeight = BASE_CELL_HEIGHT * zoom

        val totalTableW = headerWidth + colOffsets[colCount]
        val totalTableH = headerHeight + rowCount * cellHeight

        val maxEffX = if (viewportWidth > 0f) (totalTableW - viewportWidth).coerceAtLeast(0f) else Float.MAX_VALUE
        val maxEffY = if (viewportHeight > 0f) (totalTableH - viewportHeight).coerceAtLeast(0f) else Float.MAX_VALUE

        val effectiveTileX = (-offsetX).coerceIn(0f, maxEffX)
        val effectiveTileY = (-offsetY).coerceIn(0f, maxEffY)

        val contentMinX = (minOf(minX, maxX) - headerWidth).coerceAtLeast(0f) + effectiveTileX
        val contentMaxX = (maxOf(minX, maxX) - headerWidth).coerceAtLeast(0f) + effectiveTileX

        val contentMinY = (minOf(minY, maxY) - headerHeight).coerceAtLeast(0f) + effectiveTileY
        val contentMaxY = (maxOf(minY, maxY) - headerHeight).coerceAtLeast(0f) + effectiveTileY

        val startCol = findColumnAtContentX(colOffsets, contentMinX)
        val endCol = findColumnAtContentX(colOffsets, contentMaxX).coerceAtLeast(startCol)

        val startRow = (contentMinY / cellHeight).toInt().coerceIn(0, rowCount - 1)
        val endRow = (contentMaxY / cellHeight).toInt().coerceIn(startRow, rowCount - 1)

        return CellRange(
            startRow = startRow,
            startCol = startCol,
            endRow = endRow,
            endCol = endCol
        )
    }
}