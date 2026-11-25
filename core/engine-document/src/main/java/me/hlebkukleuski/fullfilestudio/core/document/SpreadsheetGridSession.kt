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

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import me.hlebkukleuski.fullfilestudio.core.engine.grid.SpreadsheetCoordinateUtils

data class SheetBorder(val style: Int, val color: Int? = null) {
    companion object {
        const val NONE = 0
        const val THIN = 1
        const val MEDIUM = 2
        const val DASHED = 3
        const val DOTTED = 4
        const val THICK = 5
        const val DOUBLE = 6
        const val HAIR = 7
        const val MEDIUM_DASHED = 8
        const val DASH_DOT = 9
        const val MEDIUM_DASH_DOT = 10
        const val DASH_DOT_DOT = 11
        const val MEDIUM_DASH_DOT_DOT = 12
        const val SLANT_DASH_DOT = 13

        fun ooxml(name: String?): Int = when (name?.lowercase(Locale.ROOT)) {
            "thin" -> THIN
            "medium" -> MEDIUM
            "dashed" -> DASHED
            "dotted" -> DOTTED
            "thick" -> THICK
            "double" -> DOUBLE
            "hair" -> HAIR
            "mediumdashed" -> MEDIUM_DASHED
            "dashdot" -> DASH_DOT
            "mediumdashdot" -> MEDIUM_DASH_DOT
            "dashdotdot" -> DASH_DOT_DOT
            "mediumdashdotdot" -> MEDIUM_DASH_DOT_DOT
            "slantdashdot" -> SLANT_DASH_DOT
            else -> NONE
        }
    }
}

class SheetFill(val bg: Int, val pattern: Int = 0, val fg: Int? = null)

data class SheetCellStyle(
    val fill: Int? = null,
    val pattern: Int = 0,
    val patternColor: Int? = null,
    val textColor: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Int = 0,
    val strike: Boolean = false,
    val fontSize: Float = 0f,
    val fontName: String? = null,
    val script: Int = 0,
    val hAlign: Int = H_GENERAL,
    val vAlign: Int = V_BOTTOM,
    val wrap: Boolean = false,
    val indent: Int = 0,
    val rotation: Int = 0,
    val shrink: Boolean = false,
    val left: SheetBorder? = null,
    val right: SheetBorder? = null,
    val top: SheetBorder? = null,
    val bottom: SheetBorder? = null,
    val diagonalDown: SheetBorder? = null,
    val diagonalUp: SheetBorder? = null
) {
    fun withFill(f: SheetFill?): SheetCellStyle = copy(fill = f?.bg, pattern = f?.pattern ?: 0, patternColor = f?.fg)

    val hasBorders: Boolean get() = left != null || right != null || top != null || bottom != null || diagonalDown != null || diagonalUp != null

    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        const val H_GENERAL = 0
        const val H_LEFT = 1
        const val H_CENTER = 2
        const val H_RIGHT = 3
        const val H_FILL = 4
        const val H_JUSTIFY = 5
        const val H_CENTER_CONTINUOUS = 6
        const val H_DISTRIBUTED = 7

        const val V_TOP = 0
        const val V_CENTER = 1
        const val V_BOTTOM = 2
        const val V_JUSTIFY = 3
        const val V_DISTRIBUTED = 4

        val DEFAULT = SheetCellStyle()

        fun hAlign(name: String?): Int = when (name?.lowercase(Locale.ROOT)) {
            "left" -> H_LEFT
            "center" -> H_CENTER
            "right" -> H_RIGHT
            "fill" -> H_FILL
            "justify" -> H_JUSTIFY
            "centercontinuous" -> H_CENTER_CONTINUOUS
            "distributed" -> H_DISTRIBUTED
            else -> H_GENERAL
        }

        fun vAlign(name: String?): Int = when (name?.lowercase(Locale.ROOT)) {
            "top" -> V_TOP
            "center" -> V_CENTER
            "justify" -> V_JUSTIFY
            "distributed" -> V_DISTRIBUTED
            else -> V_BOTTOM
        }

        private val OOXML_PATTERNS = mapOf(
            "mediumgray" to 2, "darkgray" to 3, "lightgray" to 4, "darkhorizontal" to 5, "darkvertical" to 6,
            "darkdown" to 7, "darkup" to 8, "darkgrid" to 9, "darktrellis" to 10, "lighthorizontal" to 11,
            "lightvertical" to 12, "lightdown" to 13, "lightup" to 14, "lightgrid" to 15, "lighttrellis" to 16,
            "gray125" to 17, "gray0625" to 18
        )

        fun patternFill(pattern: String?, fg: Int?, bg: Int?): SheetFill? {
            val p = pattern?.lowercase(Locale.ROOT) ?: return null
            if (p == "none") return null
            if (p == "solid") return SheetFill(fg ?: bg ?: Color.BLACK)
            val code = OOXML_PATTERNS[p] ?: return null
            return SheetFill(bg ?: Color.WHITE, code, fg ?: Color.BLACK)
        }

        fun biffPattern(fls: Int, fg: Int?, bg: Int?): SheetFill? {
            if (fls <= 0) return null
            if (fls == 1) return SheetFill(fg ?: Color.BLACK)
            if (fls > 18) return SheetFill(blend(bg ?: Color.WHITE, fg ?: Color.BLACK, 0.5f))
            return SheetFill(bg ?: Color.WHITE, fls, fg ?: Color.BLACK)
        }

        private val PATTERN_ROWS = mapOf(
            2 to intArrayOf(0xAA, 0x55),
            3 to intArrayOf(0x77, 0xDD),
            4 to intArrayOf(0x88, 0x22),
            5 to intArrayOf(0xFF, 0xFF, 0x00, 0x00),
            6 to intArrayOf(0xCC),
            7 to intArrayOf(0xCC, 0x66, 0x33, 0x99),
            8 to intArrayOf(0x33, 0x66, 0xCC, 0x99),
            9 to intArrayOf(0xCC, 0xCC, 0x33, 0x33),
            10 to intArrayOf(0xFF, 0x66, 0xFF, 0x99),
            11 to intArrayOf(0xFF, 0x00, 0x00, 0x00),
            12 to intArrayOf(0x88),
            13 to intArrayOf(0x88, 0x44, 0x22, 0x11),
            14 to intArrayOf(0x11, 0x22, 0x44, 0x88),
            15 to intArrayOf(0xFF, 0x88, 0x88, 0x88),
            16 to intArrayOf(0x88, 0x55, 0x22, 0x55),
            17 to intArrayOf(0x88, 0x00, 0x22, 0x00),
            18 to intArrayOf(0x88, 0x00, 0x00, 0x00, 0x22, 0x00, 0x00, 0x00)
        )

        fun patternMask(code: Int): IntArray? {
            val rows = PATTERN_ROWS[code] ?: return null
            return IntArray(8) { rows[it % rows.size] }
        }

        fun blend(base: Int, over: Int, t: Float): Int = Color.rgb(
            (Color.red(base) + (Color.red(over) - Color.red(base)) * t).toInt().coerceIn(0, 255),
            (Color.green(base) + (Color.green(over) - Color.green(base)) * t).toInt().coerceIn(0, 255),
            (Color.blue(base) + (Color.blue(over) - Color.blue(base)) * t).toInt().coerceIn(0, 255)
        )
    }
}

object SheetCellFlags {
    const val NUMBER = 1
    const val BOOL = 2
    const val ERROR = 4
    const val GENERAL = 8
}

class SheetGridGeometry(
    val headerWidth: Float,
    val headerHeight: Float,
    val effX: Float,
    val effY: Float,
    val zoom: Float,
    val colOffsets: FloatArray,
    val colCount: Int,
    private val rowPrefix: FloatArray,
    val rowCount: Int,
    private val defaultColWidth: Float,
    private val defaultRowHeight: Float,
    val viewWidth: Float,
    val viewHeight: Float
) {
    fun colLeft(c: Int): Float = if (c <= colCount) colOffsets[c.coerceAtLeast(0)] else colOffsets[colCount] + (c - colCount) * defaultColWidth * zoom
    fun colWidth(c: Int): Float = colLeft(c + 1) - colLeft(c)
    fun rowTop(r: Int): Float = if (r <= rowCount) rowPrefix[r.coerceAtLeast(0)] * zoom else (rowPrefix[rowCount] + (r - rowCount) * defaultRowHeight) * zoom
    fun rowHeight(r: Int): Float = rowTop(r + 1) - rowTop(r)
    fun screenX(contentX: Float): Float = headerWidth + contentX - effX
    fun screenY(contentY: Float): Float = headerHeight + contentY - effY
}

abstract class SpreadsheetGridSession : DocumentSession {
    @Volatile protected var isClosed = false
    @Volatile protected var lastErrorMsg: String? = null
    @Volatile protected var activeSheetIdx = 0

    data class MergedCellRegion(
        val firstRow: Int,
        val lastRow: Int,
        val firstCol: Int,
        val lastCol: Int
    )

    class SheetModel(
        val name: String,
        val rows: List<List<String>>,
        val styles: Map<Long, SheetCellStyle> = emptyMap(),
        val colWidthsPx: FloatArray? = null,
        val mergedRegions: List<MergedCellRegion> = emptyList(),
        val drawings: List<DmlCellAnchor> = emptyList(),
        val chartSheet: Boolean = false,
        val rowHeightsPx: FloatArray? = null,
        val flags: Map<Int, ByteArray> = emptyMap(),
        val formatColors: Map<Long, Int> = emptyMap(),
        val fills: Map<Long, Int> = emptyMap(),
        val rowStyles: Map<Int, SheetCellStyle> = emptyMap(),
        val colStyles: Map<Int, SheetCellStyle> = emptyMap(),
        val showGridLines: Boolean = true,
        val defaultFontName: String? = null,
        val defaultFontSize: Float = 11f,
        val defaultRowHeightPx: Float = SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT,
        val defaultColWidthPx: Float = SpreadsheetCoordinateUtils.BASE_CELL_WIDTH,
        val extra: Any? = null,
        val conditional: SheetConditional = SheetConditional()
    ) {
        val colCount: Int = maxOf(rows.maxOfOrNull { it.size } ?: 0, colWidthsPx?.size ?: 0)
        val rowCount: Int get() = rows.size

        fun text(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c) ?: ""

        fun style(r: Int, c: Int): SheetCellStyle? {
            val key = packCoord(r, c)
            if (conditional.styles.isNotEmpty()) conditional.styles[key]?.let { return it }
            return styles[key] ?: rowStyles[r] ?: colStyles[c]
        }

        fun baseStyle(r: Int, c: Int): SheetCellStyle? = styles[packCoord(r, c)] ?: rowStyles[r] ?: colStyles[c]

        fun displayText(r: Int, c: Int): String {
            if (conditional.texts.isNotEmpty()) conditional.texts[packCoord(r, c)]?.let { return it }
            return text(r, c)
        }

        fun flags(r: Int, c: Int): Int {
            val row = flags[r] ?: return 0
            return if (c in row.indices) row[c].toInt() and 0xFF else 0
        }
    }

    protected val sheets = mutableListOf<SheetModel>()
    @Volatile protected var drawingRenderer: DmlSlideRenderer? = null

    private class SheetCache(val rowPrefix: FloatArray, val mergesByFirstRow: List<MergedCellRegion>)

    private val caches = IdentityHashMap<SheetModel, SheetCache>()
    private val typefaces = HashMap<String, Typeface>()

    companion object {
        private const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
        private const val PAGE_INDEX_MASK = 0x000FFFFF
        const val PX_PER_PT = SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT / 15f
        const val PX_PER_EXCEL_PX = PX_PER_PT * 0.75f

        fun packCoord(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xFFFFFFFFL)

        fun excelColumnWidthPx(chars: Float, maxDigitWidth: Float = 7f): Float {
            if (!chars.isFinite() || chars <= 0f) return 0f
            return (Math.round(chars * maxDigitWidth).toFloat() * PX_PER_EXCEL_PX).coerceAtMost(4000f)
        }

        fun defaultExcelColumnWidthPx(baseChars: Float = 8f, maxDigitWidth: Float = 7f): Float =
            ((baseChars * maxDigitWidth).toInt() + 8) * PX_PER_EXCEL_PX

        fun rowFlags(store: HashMap<Int, ByteArray>, r: Int, c: Int, value: Int) {
            if (value == 0 || c < 0 || c > 16383) return
            var row = store[r]
            if (row == null || row.size <= c) {
                val n = ByteArray(maxOf(c + 1, (row?.size ?: 0) * 2).coerceAtMost(16384))
                row?.copyInto(n)
                row = n
                store[r] = row
            }
            row[c] = value.toByte()
        }

        private val LIGHT = intArrayOf(
            0xFFF5F7FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFEEF1F6.toInt(),
            0xFFD8DEE9.toInt(), 0xFF1A1C24.toInt(), 0xFF5A6275.toInt()
        )
        private val DARK = intArrayOf(
            0xFF121212.toInt(), 0xFF1E1E2E.toInt(), 0xFF181825.toInt(),
            0xFF313244.toInt(), 0xFFFFFFFF.toInt(), 0xFFBAC2DE.toInt()
        )

        private fun luminance(c: Int): Double {
            fun ch(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
        }

        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        fun adaptForDark(color: Int, bg: Int, minRatio: Double): Int {
            if (contrast(color, bg) >= minRatio) return color
            val hsl = DmlColors.rgbToHsl(color)
            val inverted = DmlColors.hslToRgb(hsl[0], hsl[1], (1f - hsl[2]).coerceIn(0.55f, 0.95f))
            return if (contrast(inverted, bg) >= minRatio) inverted else Color.rgb(0xE6, 0xE6, 0xE6)
        }
    }

    override val pageCount: Int get() = 1
    override fun getLastError(): String? = lastErrorMsg

    @Synchronized
    override fun getSheetNames(): List<String> = sheets.map { it.name }

    @Synchronized
    override fun getActiveSheetIndex(): Int = activeSheetIdx

    @Synchronized
    override fun setActiveSheetIndex(index: Int) {
        if (index in sheets.indices) activeSheetIdx = index
    }

    private fun cacheFor(sheet: SheetModel): SheetCache = caches.getOrPut(sheet) {
        val n = sheet.rowCount
        val prefix = FloatArray(n + 1)
        val heights = sheet.rowHeightsPx
        var acc = 0f
        for (r in 0 until n) {
            prefix[r] = acc
            val h = heights?.getOrNull(r)?.takeIf { it.isFinite() && it >= 0f } ?: sheet.defaultRowHeightPx
            acc += h
        }
        prefix[n] = acc
        SheetCache(prefix, sheet.mergedRegions.sortedBy { it.firstRow })
    }

    @Synchronized
    override fun getColumnWidths(): FloatArray {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return FloatArray(0)
        if (sheet.chartSheet) return FloatArray(0)
        val widths = sheet.colWidthsPx ?: return FloatArray(0)
        val n = sheet.colCount
        if (n <= 0) return FloatArray(0)
        return FloatArray(n) { c -> widths.getOrNull(c)?.takeIf { it.isFinite() && it >= 0f } ?: sheet.defaultColWidthPx }
    }

    @Synchronized
    override fun getRowHeights(): FloatArray {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return FloatArray(0)
        if (sheet.chartSheet || sheet.rowHeightsPx == null) return FloatArray(0)
        val c = cacheFor(sheet)
        return FloatArray(sheet.rowCount) { c.rowPrefix[it + 1] - c.rowPrefix[it] }
    }

    @Synchronized
    override fun getCellText(row: Int, col: Int): String {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return ""
        return sheet.text(row, col)
    }

    @Synchronized
    override fun getSelectedRangeText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return ""
        if (sheet.rows.isEmpty()) return ""
        val rMin = minOf(startRow, endRow).coerceAtLeast(0)
        val rMax = minOf(maxOf(startRow, endRow), sheet.rows.size - 1)
        if (rMin > rMax) return ""
        val maxCols = sheet.colCount
        if (maxCols == 0) return ""
        val cMin = minOf(startCol, endCol).coerceAtLeast(0)
        val cMax = minOf(maxOf(startCol, endCol), maxCols - 1)
        if (cMin > cMax) return ""
        val sb = StringBuilder()
        for (r in rMin..rMax) {
            for (c in cMin..cMax) {
                sb.append(sheet.text(r, c))
                if (c < cMax) sb.append('\t')
            }
            if (r < rMax) sb.append('\n')
        }
        return sb.toString()
    }

    @Synchronized
    override fun getPageText(pageIndex: Int): String {
        val cleanIdx = pageIndex and PAGE_INDEX_MASK
        val targetIdx = if (cleanIdx in sheets.indices) cleanIdx else activeSheetIdx
        val sheet = sheets.getOrNull(targetIdx) ?: return ""
        return sheet.rows.joinToString("\n") { it.joinToString("\t") }
    }

    @Synchronized
    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        for (sIdx in startPage.coerceAtLeast(0) until sheets.size) {
            val found = sheets[sIdx].rows.any { row -> row.any { it.contains(query, ignoreCase = true) } }
            if (found) return sIdx.toLong()
        }
        return -1L
    }

    protected open fun chartSheetSize(sheet: SheetModel): Pair<Float, Float> {
        val (w, h) = chartSheetExtentEmu(sheet)
        val px = SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT / (15f * 12700f)
        return w * px to h * px
    }

    private fun chartSheetExtentEmu(sheet: SheetModel): Pair<Long, Long> {
        var w = 0L
        var h = 0L
        for (a in sheet.drawings) {
            if (a.kind == DmlCellAnchor.ABSOLUTE) { w = maxOf(w, a.x + a.cx); h = maxOf(h, a.y + a.cy) }
        }
        if (w <= 0L || h <= 0L) { w = 8_670_000L; h = 6_290_000L }
        return w.coerceAtMost(60_000_000L) to h.coerceAtMost(60_000_000L)
    }

    protected open fun renderChartSheet(sheet: SheetModel, canvas: Canvas, tileX: Int, tileY: Int, zoom: Float, bitmap: Bitmap) {
        val renderer = drawingRenderer ?: return
        val pxPerEmu = SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT / (15f * 12700f) * zoom
        val (w, h) = chartSheetExtentEmu(sheet)
        val page = RectF(-tileX.toFloat(), -tileY.toFloat(), w * pxPerEmu - tileX, h * pxPerEmu - tileY)
        canvas.drawRect(page, Paint().apply { color = Color.WHITE })
        for (a in sheet.drawings) {
            val rect = if (a.kind == DmlCellAnchor.ABSOLUTE && a.cx > 0 && a.cy > 0) {
                RectF(a.x * pxPerEmu, a.y * pxPerEmu, (a.x + a.cx) * pxPerEmu, (a.y + a.cy) * pxPerEmu).apply { offset(-tileX.toFloat(), -tileY.toFloat()) }
            } else RectF(page)
            try { renderer.drawAnchored(canvas, a.element, a.drawingPath, rect, pxPerEmu) } catch (_: Exception) {}
        }
    }

    protected open fun drawSheetOverlays(sheet: SheetModel, canvas: Canvas, g: SheetGridGeometry) {
        val renderer = drawingRenderer ?: return
        if (sheet.drawings.isEmpty()) return
        val pxPerEmu = SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT / (15f * 12700f) * g.zoom
        fun colX(c: Int, off: Long): Float = g.colLeft(c) + minOf(off * pxPerEmu, g.colWidth(c))
        fun rowY(r: Int, off: Long): Float = g.rowTop(r) + minOf(off * pxPerEmu, g.rowHeight(r))
        val view = RectF(0f, 0f, g.viewWidth, g.viewHeight)
        for (a in sheet.drawings) {
            val rect = when (a.kind) {
                DmlCellAnchor.TWO_CELL -> RectF(colX(a.fromCol, a.fromColOff), rowY(a.fromRow, a.fromRowOff), colX(a.toCol, a.toColOff), rowY(a.toRow, a.toRowOff))
                DmlCellAnchor.ONE_CELL -> {
                    val l = colX(a.fromCol, a.fromColOff)
                    val t = rowY(a.fromRow, a.fromRowOff)
                    RectF(l, t, l + a.cx * pxPerEmu, t + a.cy * pxPerEmu)
                }
                else -> RectF(a.x * pxPerEmu, a.y * pxPerEmu, (a.x + a.cx) * pxPerEmu, (a.y + a.cy) * pxPerEmu)
            }
            rect.offset(g.headerWidth - g.effX, g.headerHeight - g.effY)
            if (rect.width() <= 0f || rect.height() <= 0f || !RectF.intersects(rect, view)) continue
            try { renderer.drawAnchored(canvas, a.element, a.drawingPath, rect, pxPerEmu) } catch (_: Exception) {}
        }
    }

    protected fun padGridForDrawings(grid: ArrayList<ArrayList<String>>, drawings: List<DmlCellAnchor>, maxRows: Int, maxCols: Int) {
        val rowEmu = 15L * 12700L
        val colEmu = (SpreadsheetCoordinateUtils.BASE_CELL_WIDTH / (SpreadsheetCoordinateUtils.BASE_CELL_HEIGHT / (15f * 12700f))).toLong().coerceAtLeast(1L)
        var needRows = 0
        var needCols = 0
        for (a in drawings) {
            val r: Long
            val c: Long
            when (a.kind) {
                DmlCellAnchor.TWO_CELL -> { r = a.toRow.toLong(); c = a.toCol.toLong() }
                DmlCellAnchor.ONE_CELL -> { r = a.fromRow + a.cy / rowEmu + 1; c = a.fromCol + a.cx / colEmu + 1 }
                else -> { r = (a.y + a.cy) / rowEmu; c = (a.x + a.cx) / colEmu }
            }
            needRows = maxOf(needRows, (r + 2).coerceAtMost(maxRows.toLong()).toInt())
            needCols = maxOf(needCols, (c + 2).coerceAtMost(maxCols.toLong()).toInt())
        }
        while (grid.size < needRows) grid.add(ArrayList())
        if (grid.isNotEmpty()) {
            val first = grid[0]
            while (first.size < needCols) first.add("")
        }
    }

    @Synchronized
    override fun getDocumentWidth(): Float {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return 0f
        if (sheet.chartSheet) return chartSheetSize(sheet).first
        val n = sheet.colCount
        if (sheet.rows.isEmpty() || n == 0) return 0f
        val widths = sheet.colWidthsPx
        val content = if (widths != null) {
            var sum = 0f
            for (c in 0 until n) sum += SpreadsheetCoordinateUtils.effectiveColumnWidth(widths.getOrNull(c) ?: sheet.defaultColWidthPx)
            sum
        } else n * SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
        return SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH + content
    }

    @Synchronized
    override fun getDocumentHeight(): Float {
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return 0f
        if (sheet.chartSheet) return chartSheetSize(sheet).second
        if (sheet.rows.isEmpty()) return 0f
        return SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT + cacheFor(sheet).rowPrefix[sheet.rowCount]
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    private fun typeface(name: String?, bold: Boolean, italic: Boolean): Typeface {
        val key = "${name ?: ""}|$bold|$italic"
        return typefaces.getOrPut(key) { OfficeFonts.resolve(name, bold, italic) }
    }

    private class Painter(
        val sheet: SheetModel,
        val g: SheetGridGeometry,
        val light: Boolean,
        val theme: IntArray,
        val canvas: Canvas,
        val session: SpreadsheetGridSession
    ) {
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val fill = Paint().apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val zoom = g.zoom
        val pad = 5f * zoom

        private val shaders = HashMap<Long, android.graphics.BitmapShader>()

        fun pattern(code: Int, color: Int): Paint? {
            val key = (code.toLong() shl 32) or (color.toLong() and 0xFFFFFFFFL)
            val shader = shaders.getOrPut(key) {
                val mask = SheetCellStyle.patternMask(code) ?: return null
                val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                for (y in 0 until 8) for (x in 0 until 8) {
                    if ((mask[y] shr (7 - x)) and 1 == 1) bmp.setPixel(x, y, color)
                }
                android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
            }
            val m = android.graphics.Matrix()
            val unit = (PX_PER_EXCEL_PX * zoom).coerceAtLeast(1f)
            m.setScale(unit, unit)
            m.postTranslate(g.screenX(0f), g.screenY(0f))
            shader.setLocalMatrix(m)
            return Paint().apply { this.shader = shader; isFilterBitmap = false }
        }

        fun fillCell(st: SheetCellStyle, l: Float, t: Float, r: Float, b: Float) {
            val f = st.fill ?: return
            fill.color = f
            canvas.drawRect(l, t, r, b, fill)
            val pc = st.patternColor
            if (st.pattern > 1 && pc != null) pattern(st.pattern, pc)?.let { canvas.drawRect(l, t, r, b, it) }
        }

        fun setFont(st: SheetCellStyle?) {
            val size = (st?.fontSize?.takeIf { it > 0f } ?: sheet.defaultFontSize).coerceIn(1f, 409f)
            text.typeface = session.typeface(st?.fontName ?: sheet.defaultFontName, st?.bold == true, st?.italic == true)
            text.textSize = size * PX_PER_PT * zoom * (if (st != null && st.script != 0) 0.66f else 1f)
            text.isUnderlineText = (st?.underline ?: 0) > 0
            text.isStrikeThruText = st?.strike == true
            text.textSkewX = 0f
        }

        fun textColor(st: SheetCellStyle?, fmtColor: Int?, fillColor: Int?): Int {
            val explicit = fmtColor ?: st?.textColor
            if (explicit == null) return if (fillColor != null) Color.BLACK else theme[4]
            if (fillColor == null && !light) return adaptForDark(explicit, theme[1], 3.0)
            return explicit
        }

        fun borderColor(b: SheetBorder, fillColor: Int?): Int {
            val c = b.color ?: Color.BLACK
            if (!light && fillColor == null) return adaptForDark(c, theme[1], 2.2)
            return c
        }
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val light = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val theme = if (light) LIGHT else DARK
        val canvas = Canvas(bitmap)
        canvas.drawColor(theme[0])
        val sheet = sheets.getOrNull(activeSheetIdx) ?: return
        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 4f)
        if (sheet.chartSheet) {
            renderChartSheet(sheet, canvas, tileX, tileY, zoom, bitmap)
            return
        }
        val rowCount = sheet.rowCount
        val colCount = sheet.colCount
        if (rowCount <= 0 || colCount <= 0) return
        val cache = cacheFor(sheet)
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val headerHeight = SpreadsheetCoordinateUtils.BASE_HEADER_HEIGHT * zoom
        val headerWidth = SpreadsheetCoordinateUtils.BASE_HEADER_WIDTH * zoom
        val colOffsets = SpreadsheetCoordinateUtils.computeScaledColumnOffsets(colCount, sheet.colWidthsPx, bw, zoom)
        val totalW = headerWidth + colOffsets[colCount]
        val totalH = headerHeight + cache.rowPrefix[rowCount] * zoom
        val effX = tileX.toFloat().coerceIn(0f, (totalW - bw).coerceAtLeast(0f))
        val effY = tileY.toFloat().coerceIn(0f, (totalH - bh).coerceAtLeast(0f))
        val defColW = if (sheet.colWidthsPx != null) sheet.defaultColWidthPx else SpreadsheetCoordinateUtils.BASE_CELL_WIDTH
        val g = SheetGridGeometry(headerWidth, headerHeight, effX, effY, zoom, colOffsets, colCount, cache.rowPrefix, rowCount, defColW, sheet.defaultRowHeightPx, bw, bh)
        val p = Painter(sheet, g, light, theme, canvas, this)

        val startCol = SpreadsheetCoordinateUtils.findColumnAtContentX(colOffsets, effX)
        var endCol = startCol
        while (endCol < 16383 && g.screenX(g.colLeft(endCol + 1)) < bw) endCol++
        var startRow = 0
        run {
            var lo = 0
            var hi = rowCount - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (cache.rowPrefix[mid + 1] * zoom <= effY) lo = mid + 1 else hi = mid - 1
            }
            startRow = lo.coerceAtMost(rowCount - 1).coerceAtLeast(0)
        }
        var endRow = startRow
        while (endRow < 1_048_575 && g.screenY(g.rowTop(endRow + 1)) < bh) endRow++
        val lastDataCol = min(endCol, colCount - 1)
        val lastDataRow = min(endRow, rowCount - 1)

        canvas.drawRect(headerWidth, headerHeight, bw, bh, Paint().apply { color = theme[1] })

        val merges = cache.mergesByFirstRow.filter { it.firstRow <= endRow && it.lastRow >= startRow && it.firstCol <= endCol && it.lastCol >= startCol }
        val covered = HashMap<Long, MergedCellRegion>()
        for (m in merges) {
            for (r in max(m.firstRow, startRow)..min(m.lastRow, endRow)) for (c in max(m.firstCol, startCol)..min(m.lastCol, endCol)) {
                covered[packCoord(r, c)] = m
            }
        }

        canvas.save()
        canvas.clipRect(headerWidth, headerHeight, bw, bh)

        if (sheet.showGridLines) {
            val grid = Paint().apply { color = theme[3]; strokeWidth = 2f; style = Paint.Style.STROKE }
            for (c in startCol..endCol) {
                if (g.colWidth(c) <= 0f) continue
                val x = g.screenX(g.colLeft(c + 1))
                canvas.drawLine(x, headerHeight, x, bh, grid)
            }
            for (r in startRow..endRow) {
                if (g.rowHeight(r) <= 0f) continue
                val y = g.screenY(g.rowTop(r + 1))
                canvas.drawLine(headerWidth, y, bw, y, grid)
            }
        }

        for (r in startRow..lastDataRow) {
            val rh = g.rowHeight(r)
            if (rh <= 0f) continue
            val y0 = g.screenY(g.rowTop(r))
            for (c in startCol..lastDataCol) {
                if (covered.containsKey(packCoord(r, c))) continue
                val st = sheet.style(r, c) ?: continue
                val f = st.fill ?: continue
                val cw = g.colWidth(c)
                if (cw <= 0f) continue
                val x0 = g.screenX(g.colLeft(c))
                p.fillCell(st, x0 - 1f, y0 - 1f, x0 + cw + 1f, y0 + rh + 1f)
            }
        }
        for (m in merges) {
            val st = sheet.style(m.firstRow, m.firstCol)
            val x0 = g.screenX(g.colLeft(m.firstCol))
            val x1 = g.screenX(g.colLeft(m.lastCol + 1))
            val y0 = g.screenY(g.rowTop(m.firstRow))
            val y1 = g.screenY(g.rowTop(m.lastRow + 1))
            val f = st?.fill
            if (st != null && f != null) p.fillCell(st, x0 - 1f, y0 - 1f, x1 + 1f, y1 + 1f)
            else {
                p.fill.color = theme[1]
                canvas.drawRect(x0 + 1f, y0 + 1f, x1 - 1f, y1 - 1f, p.fill)
            }
        }
        if (sheet.conditional.bars.isNotEmpty()) {
            for (r in startRow..lastDataRow) {
                val rh = g.rowHeight(r)
                if (rh <= 0f) continue
                for (c in startCol..lastDataCol) {
                    val bar = sheet.conditional.bars[packCoord(r, c)] ?: continue
                    val cw = g.colWidth(c)
                    if (cw <= 0f) continue
                    drawDataBar(p, bar, g.screenX(g.colLeft(c)), g.screenY(g.rowTop(r)), cw, rh)
                }
            }
        }

        for (r in startRow..lastDataRow) {
            if (g.rowHeight(r) <= 0f) continue
            var scanFrom = startCol
            var back = startCol - 1
            val limit = max(0, startCol - 256)
            while (back >= limit) {
                if (sheet.text(r, back).isNotEmpty()) { scanFrom = back; break }
                back--
            }
            var scanTo = lastDataCol
            var fwd = lastDataCol + 1
            val fLimit = min(colCount - 1, lastDataCol + 256)
            while (fwd <= fLimit) {
                if (sheet.text(r, fwd).isNotEmpty()) { scanTo = fwd; break }
                fwd++
            }
            for (c in scanFrom..scanTo) {
                val key = packCoord(r, c)
                if (covered.containsKey(key)) continue
                if (merges.isEmpty() || c in startCol..endCol || cache.mergesByFirstRow.none { r in it.firstRow..it.lastRow && c in it.firstCol..it.lastCol }) {
                    val txt = sheet.displayText(r, c)
                    if (txt.isEmpty()) continue
                    drawCellText(p, r, c, r, c, txt, covered, cache)
                }
            }
        }
        for (m in merges) {
            val txt = sheet.displayText(m.firstRow, m.firstCol)
            if (txt.isNotEmpty()) drawCellText(p, m.firstRow, m.firstCol, m.lastRow, m.lastCol, txt, covered, cache)
        }

        for (r in startRow..lastDataRow) {
            if (g.rowHeight(r) <= 0f) continue
            for (c in startCol..lastDataCol) {
                if (g.colWidth(c) <= 0f) continue
                val st = sheet.style(r, c) ?: continue
                if (!st.hasBorders) continue
                drawBorders(p, r, c, st, covered[packCoord(r, c)])
            }
        }
        for (m in merges) {
            val st = sheet.style(m.firstRow, m.firstCol) ?: continue
            val x0 = g.screenX(g.colLeft(m.firstCol))
            val x1 = g.screenX(g.colLeft(m.lastCol + 1))
            val y0 = g.screenY(g.rowTop(m.firstRow))
            val y1 = g.screenY(g.rowTop(m.lastRow + 1))
            st.diagonalDown?.let { drawBorderLine(p, x0, y0, x1, y1, it, st.fill) }
            st.diagonalUp?.let { drawBorderLine(p, x0, y1, x1, y0, it, st.fill) }
        }

        try { drawSheetOverlays(sheet, canvas, g) } catch (_: Exception) {}
        canvas.restore()

        drawHeaders(p, startCol, endCol, startRow, endRow)
    }

    private fun drawHeaders(p: Painter, startCol: Int, endCol: Int, startRow: Int, endRow: Int) {
        val g = p.g
        val canvas = p.canvas
        val theme = p.theme
        val bg = Paint().apply { color = theme[2] }
        val sep = Paint().apply { color = theme[3]; strokeWidth = 2f; style = Paint.Style.STROKE }
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme[5]
            textSize = 24f * g.zoom
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val fm = label.fontMetrics
        canvas.drawRect(0f, 0f, g.viewWidth, g.headerHeight, bg)
        canvas.drawRect(0f, g.headerHeight, g.headerWidth, g.viewHeight, bg)
        canvas.save()
        canvas.clipRect(g.headerWidth, 0f, g.viewWidth, g.headerHeight)
        for (c in startCol..endCol) {
            val w = g.colWidth(c)
            if (w <= 0f) continue
            val x0 = g.screenX(g.colLeft(c))
            canvas.save()
            canvas.clipRect(x0, 0f, x0 + w, g.headerHeight)
            canvas.drawText(SpreadsheetCoordinateUtils.columnIndexToExcelLetters(c), x0 + w / 2f, (g.headerHeight - fm.ascent - fm.descent) / 2f, label)
            canvas.restore()
            canvas.drawLine(x0 + w, 0f, x0 + w, g.headerHeight, sep)
        }
        canvas.restore()
        canvas.save()
        canvas.clipRect(0f, g.headerHeight, g.headerWidth, g.viewHeight)
        for (r in startRow..endRow) {
            val h = g.rowHeight(r)
            if (h <= 0f) continue
            val y0 = g.screenY(g.rowTop(r))
            if (h >= label.textSize * 0.8f) {
                canvas.drawText("${r + 1}", g.headerWidth / 2f, y0 + (h - fm.ascent - fm.descent) / 2f, label)
            }
            canvas.drawLine(0f, y0 + h, g.headerWidth, y0 + h, sep)
        }
        canvas.restore()
        canvas.drawLine(0f, g.headerHeight, g.viewWidth, g.headerHeight, sep)
        canvas.drawLine(g.headerWidth, 0f, g.headerWidth, g.viewHeight, sep)
        canvas.drawRect(0f, 0f, g.headerWidth, g.headerHeight, bg)
        canvas.drawLine(0f, g.headerHeight, g.headerWidth, g.headerHeight, sep)
        canvas.drawLine(g.headerWidth, 0f, g.headerWidth, g.headerHeight, sep)
    }

    private fun isEmptyForOverflow(sheet: SheetModel, r: Int, c: Int, cache: SheetCache, covered: Map<Long, MergedCellRegion>): Boolean {
        if (c < 0 || c > 16383) return false
        if (sheet.text(r, c).isNotEmpty()) return false
        if (covered.containsKey(packCoord(r, c))) return false
        if (cache.mergesByFirstRow.isNotEmpty() && cache.mergesByFirstRow.any { r in it.firstRow..it.lastRow && c in it.firstCol..it.lastCol }) return false
        return true
    }

    private fun drawCellText(
        p: Painter, r0: Int, c0: Int, r1: Int, c1: Int, raw: String,
        covered: Map<Long, MergedCellRegion>, cache: SheetCache
    ) {
        val sheet = p.sheet
        val g = p.g
        val canvas = p.canvas
        val zoom = p.zoom
        val st = sheet.style(r0, c0)
        val flags = sheet.flags(r0, c0)
        val key = packCoord(r0, c0)
        val x0 = g.screenX(g.colLeft(c0))
        val x1 = g.screenX(g.colLeft(c1 + 1))
        val y0 = g.screenY(g.rowTop(r0))
        val y1 = g.screenY(g.rowTop(r1 + 1))
        val cellW = x1 - x0
        val cellH = y1 - y0
        if (cellW <= 0f || cellH <= 0f) return
        p.setFont(st)
        val paint = p.text
        val cfColor = if (sheet.conditional.styles.isNotEmpty()) sheet.conditional.styles[key]?.textColor?.takeIf { it != sheet.baseStyle(r0, c0)?.textColor } else null
        paint.color = p.textColor(st, cfColor ?: sheet.formatColors[key], st?.fill)
        val isNumber = (flags and SheetCellFlags.NUMBER) != 0
        val isBoolOrErr = (flags and (SheetCellFlags.BOOL or SheetCellFlags.ERROR)) != 0
        var h = st?.hAlign ?: SheetCellStyle.H_GENERAL
        if (h == SheetCellStyle.H_GENERAL) h = when {
            isNumber -> SheetCellStyle.H_RIGHT
            isBoolOrErr -> SheetCellStyle.H_CENTER
            else -> SheetCellStyle.H_LEFT
        }
        val v = st?.vAlign ?: SheetCellStyle.V_BOTTOM
        var indentPx = (st?.indent ?: 0) * 24f * zoom
        val icon = sheet.conditional.icons[key]
        if (icon != null) {
            val fm0 = paint.fontMetrics
            val size = (fm0.descent - fm0.ascent) * 0.8f
            val cy = when (st?.vAlign ?: SheetCellStyle.V_BOTTOM) {
                SheetCellStyle.V_TOP -> y0 + p.pad * 0.4f + (fm0.descent - fm0.ascent) / 2f
                SheetCellStyle.V_CENTER -> (y0 + y1) / 2f
                else -> y1 - p.pad * 0.4f - (fm0.descent - fm0.ascent) / 2f
            }
            canvas.save()
            canvas.clipRect(max(x0, g.headerWidth), max(y0, g.headerHeight), x1, y1)
            drawIcon(p, icon, x0 + p.pad + size / 2f, cy, size)
            canvas.restore()
            if (h != SheetCellStyle.H_RIGHT && h != SheetCellStyle.H_CENTER) indentPx += size + p.pad
        }
        if (key in sheet.conditional.hideText) return
        val rotation = st?.rotation ?: 0
        val wrap = (st?.wrap == true || h == SheetCellStyle.H_JUSTIFY || h == SheetCellStyle.H_DISTRIBUTED || v == SheetCellStyle.V_JUSTIFY || v == SheetCellStyle.V_DISTRIBUTED || raw.contains('\n')) && rotation == 0
        val avail = (cellW - 2 * p.pad - indentPx).coerceAtLeast(1f)

        canvas.save()
        if (rotation == 255) {
            canvas.clipRect(x0, y0, x1, y1)
            val fm = paint.fontMetrics
            val lineH = fm.descent - fm.ascent
            val total = lineH * raw.length
            var y = when (v) {
                SheetCellStyle.V_TOP -> y0 + p.pad
                SheetCellStyle.V_CENTER -> y0 + (cellH - total) / 2f
                else -> y1 - p.pad - total
            } - fm.ascent
            val cx = when (h) {
                SheetCellStyle.H_LEFT -> x0 + p.pad + paint.measureText("W") / 2f
                SheetCellStyle.H_RIGHT -> x1 - p.pad - paint.measureText("W") / 2f
                else -> (x0 + x1) / 2f
            }
            for (ch in raw) {
                val s = ch.toString()
                canvas.drawText(s, cx - paint.measureText(s) / 2f, y, paint)
                y += lineH
            }
            canvas.restore()
            return
        }
        if (rotation != 0) {
            canvas.clipRect(x0, y0, x1, y1)
            val deg = if (rotation <= 90) -rotation.toFloat() else (rotation - 90).toFloat()
            val tw = paint.measureText(raw)
            val fm = paint.fontMetrics
            val th = fm.descent - fm.ascent
            val rad = Math.toRadians(abs(deg).toDouble())
            val bw = (abs(tw * Math.cos(rad)) + abs(th * Math.sin(rad))).toFloat()
            val bh = (abs(tw * Math.sin(rad)) + abs(th * Math.cos(rad))).toFloat()
            val cx = when (h) {
                SheetCellStyle.H_LEFT -> x0 + p.pad + bw / 2f
                SheetCellStyle.H_RIGHT -> x1 - p.pad - bw / 2f
                else -> (x0 + x1) / 2f
            }
            val cy = when (v) {
                SheetCellStyle.V_TOP -> y0 + p.pad + bh / 2f
                SheetCellStyle.V_CENTER -> (y0 + y1) / 2f
                else -> y1 - p.pad - bh / 2f
            }
            canvas.translate(cx, cy)
            canvas.rotate(deg)
            canvas.drawText(raw, -tw / 2f, -(fm.ascent + fm.descent) / 2f, paint)
            canvas.restore()
            return
        }
        if (wrap) {
            canvas.clipRect(max(x0, g.headerWidth), max(y0, g.headerHeight), x1, y1)
            val align = when (h) {
                SheetCellStyle.H_CENTER, SheetCellStyle.H_CENTER_CONTINUOUS, SheetCellStyle.H_DISTRIBUTED -> Layout.Alignment.ALIGN_CENTER
                SheetCellStyle.H_RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            val builder = StaticLayout.Builder.obtain(raw, 0, raw.length, paint, avail.toInt().coerceAtLeast(1))
                .setAlignment(align)
                .setIncludePad(false)
            if (h == SheetCellStyle.H_JUSTIFY || h == SheetCellStyle.H_DISTRIBUTED) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            val layout = builder.build()
            val ty = when (v) {
                SheetCellStyle.V_TOP -> y0 + p.pad * 0.4f
                SheetCellStyle.V_CENTER, SheetCellStyle.V_JUSTIFY, SheetCellStyle.V_DISTRIBUTED -> y0 + (cellH - layout.height) / 2f
                else -> y1 - p.pad * 0.4f - layout.height
            }
            val tx = x0 + p.pad + if (h == SheetCellStyle.H_RIGHT) 0f else indentPx
            canvas.translate(tx, if (layout.height > cellH && v != SheetCellStyle.V_TOP) max(ty, y0) else ty)
            layout.draw(canvas)
            canvas.restore()
            return
        }

        var text = raw
        var width = paint.measureText(text)
        val fillSpec = sheet.fills[key]
        val single = r0 == r1 && c0 == c1
        if (fillSpec != null) {
            val at = (fillSpec ushr 16).coerceIn(0, text.length)
            val ch = (fillSpec and 0xFFFF).toChar()
            if (width < avail) {
                val unit = paint.measureText(ch.toString()).coerceAtLeast(0.5f)
                val n = ((avail - width) / unit).toInt().coerceIn(0, 2000)
                text = text.substring(0, at) + ch.toString().repeat(n) + text.substring(at)
                width = paint.measureText(text)
            }
            h = SheetCellStyle.H_LEFT
        }
        if (h == SheetCellStyle.H_FILL && width > 0f && width < avail) {
            val n = (avail / width).toInt().coerceIn(1, 500)
            text = raw.repeat(n)
            width = paint.measureText(text)
            h = SheetCellStyle.H_LEFT
        }
        if (st?.shrink == true && width > avail) {
            paint.textSize *= (avail / width).coerceAtLeast(0.2f)
            width = paint.measureText(text)
        }
        if (isNumber && width > avail) {
            var replaced: String? = null
            if ((flags and SheetCellFlags.GENERAL) != 0) {
                val dec = ExcelNumberFormat.decimalSeparator
                val d = raw.replace(dec, '.').toDoubleOrNull()
                if (d != null && raw.contains(dec) && !raw.contains('E')) {
                    var decimals = raw.substringAfter(dec).length - 1
                    while (decimals >= 0) {
                        val t = java.math.BigDecimal(d).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', dec)
                        if (paint.measureText(t) <= avail) { replaced = t; break }
                        decimals--
                    }
                }
            }
            if (replaced == null) {
                val unit = paint.measureText("#").coerceAtLeast(0.5f)
                replaced = "#".repeat((avail / unit).toInt().coerceIn(1, 255))
            }
            text = replaced
            width = paint.measureText(text)
        }

        var clipL = x0
        var clipR = x1
        if (single && !isNumber && width > avail && sheet.fills[key] == null) {
            val need = width - avail
            when (h) {
                SheetCellStyle.H_LEFT -> {
                    var c = c1 + 1
                    var gained = 0f
                    while (gained < need && isEmptyForOverflow(sheet, r0, c, cache, covered)) { gained += g.colWidth(c); clipR = g.screenX(g.colLeft(c + 1)); c++ ; if (c > c1 + 512) break }
                }
                SheetCellStyle.H_RIGHT -> {
                    var c = c0 - 1
                    var gained = 0f
                    while (c >= 0 && gained < need && isEmptyForOverflow(sheet, r0, c, cache, covered)) { gained += g.colWidth(c); clipL = g.screenX(g.colLeft(c)); c--; if (c < c0 - 512) break }
                }
                SheetCellStyle.H_CENTER, SheetCellStyle.H_CENTER_CONTINUOUS -> {
                    var l = c0 - 1
                    var rr = c1 + 1
                    var gained = 0f
                    while (gained < need) {
                        val okL = l >= 0 && isEmptyForOverflow(sheet, r0, l, cache, covered)
                        val okR = isEmptyForOverflow(sheet, r0, rr, cache, covered)
                        if (!okL || !okR) break
                        gained += 2 * min(g.colWidth(l), g.colWidth(rr))
                        clipL = g.screenX(g.colLeft(l))
                        clipR = g.screenX(g.colLeft(rr + 1))
                        l--
                        rr++
                        if (rr > c1 + 256) break
                    }
                }
            }
        }
        val drawX = when (h) {
            SheetCellStyle.H_CENTER, SheetCellStyle.H_CENTER_CONTINUOUS, SheetCellStyle.H_DISTRIBUTED, SheetCellStyle.H_JUSTIFY ->
                if (width <= avail || clipL < x0 || clipR > x1) x0 + (cellW - width) / 2f else x0 + p.pad
            SheetCellStyle.H_RIGHT -> x1 - p.pad - width - (if (st?.hAlign == SheetCellStyle.H_RIGHT) indentPx else 0f)
            else -> x0 + p.pad + indentPx
        }
        val fm = paint.fontMetrics
        val baseline = when (v) {
            SheetCellStyle.V_TOP -> y0 + p.pad * 0.4f - fm.ascent
            SheetCellStyle.V_CENTER, SheetCellStyle.V_JUSTIFY, SheetCellStyle.V_DISTRIBUTED -> y0 + (cellH - (fm.descent - fm.ascent)) / 2f - fm.ascent
            else -> y1 - p.pad * 0.4f - fm.descent
        }
        canvas.clipRect(max(clipL + 1f, g.headerWidth), max(y0 + 1f, g.headerHeight), clipR - 1f, y1 - 1f)
        val scriptShift = when (st?.script) {
            1 -> fm.ascent * 0.45f
            2 -> -fm.ascent * 0.2f
            else -> 0f
        }
        canvas.drawText(text, drawX, baseline + scriptShift, paint)
        if ((st?.underline ?: 0) >= 2) {
            val uy = baseline + fm.descent * 0.55f + 2f * zoom
            val up = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paint.color; strokeWidth = max(1f, paint.textSize / 18f) }
            canvas.drawLine(drawX, uy, drawX + width, uy, up)
        }
        canvas.restore()
    }

    private fun drawDataBar(p: Painter, bar: SheetDataBar, x: Float, y: Float, w: Float, h: Float) {
        val z = p.zoom
        val padX = 2f * PX_PER_EXCEL_PX * z
        val padY = 2f * PX_PER_EXCEL_PX * z
        val inner = (w - 2 * padX).coerceAtLeast(1f)
        val l = x + padX + inner * bar.start.coerceIn(0f, 1f)
        val r = x + padX + inner * bar.end.coerceIn(0f, 1f)
        if (r - l < 0.5f) return
        val t = y + padY
        val b = y + h - padY
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val towardLeft = bar.axis >= 0f && bar.end <= bar.axis
        if (bar.gradient) {
            val (from, to) = if (towardLeft) Color.WHITE to bar.color else bar.color to Color.WHITE
            paint.shader = android.graphics.LinearGradient(l, 0f, r, 0f, from, SheetCellStyle.blend(to, bar.color, if (to == Color.WHITE) 0.1f else 1f), android.graphics.Shader.TileMode.CLAMP)
        } else {
            paint.color = bar.color
        }
        p.canvas.drawRect(l, t, r, b, paint)
        bar.border?.let {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = it; strokeWidth = max(1f, 1.2f * z) }
            p.canvas.drawRect(l, t, r, b, bp)
        }
        if (bar.axis > 0f && bar.axis < 1f) {
            val ax = x + padX + inner * bar.axis
            val ap = Paint().apply { color = bar.axisColor; strokeWidth = max(1f, 1f * z); pathEffect = DashPathEffect(floatArrayOf(2f * z, 2f * z), 0f) }
            p.canvas.drawLine(ax, y + 1f, ax, y + h - 1f, ap)
        }
    }

    private fun drawIcon(p: Painter, icon: SheetIcon, cx: Float, cy: Float, size: Float) {
        val canvas = p.canvas
        val r = size / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = icon.color; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = icon.color; style = Paint.Style.STROKE; strokeWidth = size * 0.16f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun arrow(angleDeg: Float) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(angleDeg)
            val path = android.graphics.Path()
            path.moveTo(0f, -r)
            path.lineTo(r * 0.85f, -r * 0.05f)
            path.lineTo(r * 0.3f, -r * 0.05f)
            path.lineTo(r * 0.3f, r)
            path.lineTo(-r * 0.3f, r)
            path.lineTo(-r * 0.3f, -r * 0.05f)
            path.lineTo(-r * 0.85f, -r * 0.05f)
            path.close()
            canvas.drawPath(path, fill)
            canvas.restore()
        }
        when (icon.shape) {
            SheetConditionalFormats.ICON_ARROW_UP -> arrow(0f)
            SheetConditionalFormats.ICON_ARROW_UP_RIGHT -> arrow(45f)
            SheetConditionalFormats.ICON_ARROW_RIGHT -> arrow(90f)
            SheetConditionalFormats.ICON_ARROW_DOWN_RIGHT -> arrow(135f)
            SheetConditionalFormats.ICON_ARROW_DOWN -> arrow(180f)
            SheetConditionalFormats.ICON_CIRCLE -> {
                canvas.drawCircle(cx, cy, r * 0.9f, fill)
                canvas.drawCircle(cx, cy, r * 0.9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = SheetCellStyle.blend(icon.color, Color.BLACK, 0.3f); strokeWidth = size * 0.06f })
            }
            SheetConditionalFormats.ICON_TRIANGLE_UP, SheetConditionalFormats.ICON_TRIANGLE_DOWN -> {
                val up = icon.shape == SheetConditionalFormats.ICON_TRIANGLE_UP
                val path = android.graphics.Path()
                if (up) { path.moveTo(cx, cy - r * 0.8f); path.lineTo(cx + r * 0.9f, cy + r * 0.7f); path.lineTo(cx - r * 0.9f, cy + r * 0.7f) }
                else { path.moveTo(cx, cy + r * 0.8f); path.lineTo(cx + r * 0.9f, cy - r * 0.7f); path.lineTo(cx - r * 0.9f, cy - r * 0.7f) }
                path.close()
                canvas.drawPath(path, fill)
            }
            SheetConditionalFormats.ICON_DASH -> canvas.drawRect(cx - r * 0.8f, cy - r * 0.2f, cx + r * 0.8f, cy + r * 0.2f, fill)
            SheetConditionalFormats.ICON_DIAMOND -> {
                val path = android.graphics.Path()
                path.moveTo(cx, cy - r); path.lineTo(cx + r, cy); path.lineTo(cx, cy + r); path.lineTo(cx - r, cy); path.close()
                canvas.drawPath(path, fill)
            }
            SheetConditionalFormats.ICON_CHECK, SheetConditionalFormats.ICON_EXCLAMATION, SheetConditionalFormats.ICON_CROSS -> {
                canvas.drawCircle(cx, cy, r * 0.95f, fill)
                val w = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = size * 0.14f; strokeCap = Paint.Cap.ROUND }
                when (icon.shape) {
                    SheetConditionalFormats.ICON_CHECK -> {
                        canvas.drawLine(cx - r * 0.45f, cy, cx - r * 0.1f, cy + r * 0.35f, w)
                        canvas.drawLine(cx - r * 0.1f, cy + r * 0.35f, cx + r * 0.5f, cy - r * 0.35f, w)
                    }
                    SheetConditionalFormats.ICON_EXCLAMATION -> {
                        canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.1f, w)
                        canvas.drawPoint(cx, cy + r * 0.45f, w)
                    }
                    else -> {
                        canvas.drawLine(cx - r * 0.4f, cy - r * 0.4f, cx + r * 0.4f, cy + r * 0.4f, w)
                        canvas.drawLine(cx + r * 0.4f, cy - r * 0.4f, cx - r * 0.4f, cy + r * 0.4f, w)
                    }
                }
            }
            SheetConditionalFormats.ICON_FLAG -> {
                canvas.drawLine(cx - r * 0.6f, cy - r * 0.9f, cx - r * 0.6f, cy + r * 0.9f, Paint(stroke).apply { color = Color.DKGRAY; strokeWidth = size * 0.1f })
                val path = android.graphics.Path()
                path.moveTo(cx - r * 0.55f, cy - r * 0.9f); path.lineTo(cx + r * 0.8f, cy - r * 0.45f); path.lineTo(cx - r * 0.55f, cy); path.close()
                canvas.drawPath(path, fill)
            }
            SheetConditionalFormats.ICON_STAR -> {
                val path = android.graphics.Path()
                for (k in 0 until 10) {
                    val rr = if (k % 2 == 0) r else r * 0.45f
                    val a = Math.toRadians((k * 36 - 90).toDouble())
                    val px = cx + (rr * Math.cos(a)).toFloat()
                    val py = cy + (rr * Math.sin(a)).toFloat()
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = icon.color; strokeWidth = size * 0.07f }
                when (icon.level) {
                    0 -> canvas.drawPath(path, outline)
                    1 -> {
                        canvas.save(); canvas.clipRect(cx - r, cy - r, cx, cy + r); canvas.drawPath(path, fill); canvas.restore()
                        canvas.drawPath(path, outline)
                    }
                    else -> canvas.drawPath(path, fill)
                }
            }
            SheetConditionalFormats.ICON_BARS -> {
                val bw = size / 5f
                for (k in 0 until 4) {
                    val bh = size * (k + 1) / 4f
                    val left = cx - r + k * (bw * 1.3f)
                    val bp = if (k < icon.level) fill else Paint(fill).apply { color = Color.rgb(0xD0, 0xD0, 0xD0) }
                    canvas.drawRect(left, cy + r - bh, left + bw, cy + r, bp)
                }
            }
            SheetConditionalFormats.ICON_QUARTERS -> {
                val o = RectF(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f)
                canvas.drawCircle(cx, cy, r * 0.9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                canvas.drawArc(o, -90f, 90f * icon.level, true, fill)
                canvas.drawCircle(cx, cy, r * 0.9f, Paint(stroke).apply { strokeWidth = size * 0.08f })
            }
            SheetConditionalFormats.ICON_BOXES -> {
                val cell = size / 2f
                for (k in 0 until 4) {
                    val bx = cx - r + (k % 2) * cell
                    val by = cy - r + (k / 2) * cell
                    val bp = if (k < icon.level) fill else Paint(fill).apply { color = Color.rgb(0xD0, 0xD0, 0xD0) }
                    canvas.drawRect(bx + 1f, by + 1f, bx + cell - 1f, by + cell - 1f, bp)
                }
            }
        }
    }

    private fun drawBorders(p: Painter, r: Int, c: Int, st: SheetCellStyle, merge: MergedCellRegion?) {
        val g = p.g
        val x0 = g.screenX(g.colLeft(c))
        val x1 = g.screenX(g.colLeft(c + 1))
        val y0 = g.screenY(g.rowTop(r))
        val y1 = g.screenY(g.rowTop(r + 1))
        val fill = st.fill
        if (merge == null || r == merge.firstRow) st.top?.let { drawBorderLine(p, x0, y0, x1, y0, it, fill) }
        if (merge == null || r == merge.lastRow) st.bottom?.let { drawBorderLine(p, x0, y1, x1, y1, it, fill) }
        if (merge == null || c == merge.firstCol) st.left?.let { drawBorderLine(p, x0, y0, x0, y1, it, fill) }
        if (merge == null || c == merge.lastCol) st.right?.let { drawBorderLine(p, x1, y0, x1, y1, it, fill) }
        if (merge == null) {
            st.diagonalDown?.let { drawBorderLine(p, x0, y0, x1, y1, it, fill) }
            st.diagonalUp?.let { drawBorderLine(p, x0, y1, x1, y0, it, fill) }
        }
    }

    private fun drawBorderLine(p: Painter, ax: Float, ay: Float, bx: Float, by: Float, b: SheetBorder, fill: Int?) {
        if (b.style == SheetBorder.NONE) return
        val z = p.zoom
        val paint = p.line
        paint.color = p.borderColor(b, fill)
        paint.pathEffect = null
        paint.strokeCap = Paint.Cap.SQUARE
        val base = when (b.style) {
            SheetBorder.HAIR -> 1.2f
            SheetBorder.MEDIUM, SheetBorder.MEDIUM_DASHED, SheetBorder.MEDIUM_DASH_DOT, SheetBorder.MEDIUM_DASH_DOT_DOT, SheetBorder.SLANT_DASH_DOT -> 4f
            SheetBorder.THICK -> 6f
            else -> 2f
        }
        paint.strokeWidth = max(1f, base * z)
        val u = 3f * z
        paint.pathEffect = when (b.style) {
            SheetBorder.DASHED, SheetBorder.MEDIUM_DASHED -> DashPathEffect(floatArrayOf(3 * u, u), 0f)
            SheetBorder.DOTTED -> DashPathEffect(floatArrayOf(u * 0.7f, u * 0.7f), 0f)
            SheetBorder.HAIR -> DashPathEffect(floatArrayOf(u * 0.4f, u * 0.4f), 0f)
            SheetBorder.DASH_DOT, SheetBorder.MEDIUM_DASH_DOT, SheetBorder.SLANT_DASH_DOT -> DashPathEffect(floatArrayOf(3 * u, u, u, u), 0f)
            SheetBorder.DASH_DOT_DOT, SheetBorder.MEDIUM_DASH_DOT_DOT -> DashPathEffect(floatArrayOf(3 * u, u, u, u, u, u), 0f)
            else -> null
        }
        if (paint.pathEffect != null) paint.strokeCap = Paint.Cap.BUTT
        if (b.style == SheetBorder.DOUBLE) {
            paint.strokeWidth = max(1f, 1.5f * z)
            val off = 2.2f * z
            if (ay == by) {
                p.canvas.drawLine(ax, ay - off, bx, by - off, paint)
                p.canvas.drawLine(ax, ay + off, bx, by + off, paint)
            } else if (ax == bx) {
                p.canvas.drawLine(ax - off, ay, bx - off, by, paint)
                p.canvas.drawLine(ax + off, ay, bx + off, by, paint)
            } else {
                p.canvas.drawLine(ax, ay, bx, by, paint)
            }
            return
        }
        p.canvas.drawLine(ax, ay, bx, by, paint)
    }
}
