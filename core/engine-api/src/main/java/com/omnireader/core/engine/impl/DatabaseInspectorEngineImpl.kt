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

package com.omnireader.core.engine.impl

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OpenParams
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.system.Os
import android.text.TextPaint
import android.text.TextUtils
import com.omnireader.core.engine.DetectedFormat
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.FileSignatureDetector
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.ZeroCacheReadableSource
import com.omnireader.core.engine.modules.DatabaseInspectorEngine
import com.omnireader.core.engine.modules.DatabaseSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private const val LIGHT_THEME_PAGE_FLAG = 1 shl 30

private val DARK_SHEET_BG_COLOR = Color.rgb(0x12, 0x12, 0x12)
private val DARK_SHEET_CELL_BG_COLOR = Color.rgb(0x1E, 0x1E, 0x2E)
private val DARK_SHEET_HEADER_BG_COLOR = Color.rgb(0x18, 0x18, 0x25)
private val DARK_SHEET_GRID_COLOR = Color.rgb(0x31, 0x32, 0x44)
private val DARK_SHEET_TEXT_COLOR = Color.rgb(0xFF, 0xFF, 0xFF)
private val DARK_SHEET_HEADER_TEXT_COLOR = Color.rgb(0xBA, 0xC2, 0xDE)

private val LIGHT_SHEET_BG_COLOR = Color.rgb(0xF5, 0xF7, 0xFA)
private val LIGHT_SHEET_CELL_BG_COLOR = Color.rgb(0xFF, 0xFF, 0xFF)
private val LIGHT_SHEET_HEADER_BG_COLOR = Color.rgb(0xEE, 0xF1, 0xF6)
private val LIGHT_SHEET_GRID_COLOR = Color.rgb(0xD8, 0xDE, 0xE9)
private val LIGHT_SHEET_TEXT_COLOR = Color.rgb(0x1A, 0x1C, 0x24)
private val LIGHT_SHEET_HEADER_TEXT_COLOR = Color.rgb(0x5A, 0x62, 0x75)

interface DatabaseInspectorEngineLocal {
    fun canHandle(signature: ByteArray, extension: String): Boolean
    fun createSession(fd: FileDescriptor): DocumentSession
    fun createSessionWithCacheDir(fd: FileDescriptor, cacheDir: File?): DocumentSession
    fun createSessionWithCacheDir(fd: FileDescriptor, cacheDir: File?, extension: String): DocumentSession
    fun createSessionWithCacheDir(fd: FileDescriptor, cacheDir: File?, extension: String, password: String?): DocumentSession =
        openSafeDatabase(fd, cacheDir, extension, password)
    fun openSafeDatabase(fd: FileDescriptor, cacheDir: File? = null): DatabaseSessionLocal
    fun openSafeDatabase(fd: FileDescriptor, cacheDir: File? = null, extension: String = ""): DatabaseSessionLocal
    fun openSafeDatabase(fd: FileDescriptor, cacheDir: File? = null, extension: String = "", password: String? = null): DatabaseSessionLocal =
        openSafeDatabase(fd, cacheDir, extension)
}

interface DatabaseSessionLocal : DocumentSession, DatabaseSession {
    override val isSchemaTrustedStatus: Boolean
    override fun fetchVisibleRows(offsetIndex: Long, limit: Int): List<List<String>>
}

class DatabaseInspectorEngineImpl : DatabaseInspectorEngineLocal, DatabaseInspectorEngine {
    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        if (ext in setOf(
                "db", "db3", "s3db", "sl3", "sqlite", "sqlite3",
                "mdb", "accdb", "dbf", "parquet", "duckdb", "realm", "bson", "jsonl", "ndjson"
            )
        ) {
            return true
        }
        if (signature.size >= 20 &&
            signature[0].toInt() == 0 && signature[1].toInt() == 1 &&
            signature[2].toInt() == 0 && signature[3].toInt() == 0
        ) {
            val magic = String(signature, 4, 15, StandardCharsets.US_ASCII)
            if (magic.startsWith("Standard Jet DB") || magic.startsWith("Standard ACE DB")) {
                return true
            }
        }
        if (signature.size >= 4 &&
            signature[0] == 'P'.code.toByte() &&
            signature[1] == 'A'.code.toByte() &&
            signature[2] == 'R'.code.toByte() &&
            (signature[3] == '1'.code.toByte() || signature[3] == 'E'.code.toByte())
        ) {
            return true
        }
        return false
    }

    override fun createSession(fd: FileDescriptor): DocumentSession {
        return openSafeDatabase(fd, null, "")
    }

    override fun createSessionWithCacheDir(fd: FileDescriptor, cacheDir: File?): DocumentSession {
        return openSafeDatabase(fd, cacheDir, "")
    }

    override fun createSessionWithCacheDir(fd: FileDescriptor, cacheDir: File?, extension: String): DocumentSession {
        return openSafeDatabase(fd, cacheDir, extension, null)
    }

    override fun createSessionWithCacheDir(fd: FileDescriptor, cacheDir: File?, extension: String, password: String?): DocumentSession {
        return openSafeDatabase(fd, cacheDir, extension, password)
    }

    override fun openSafeDatabase(fd: FileDescriptor): DatabaseSessionLocal {
        return openSafeDatabase(fd, null, "", null)
    }

    override fun openSafeDatabase(fd: FileDescriptor, cacheDir: File?): DatabaseSessionLocal {
        return openSafeDatabase(fd, cacheDir, "", null)
    }

    override fun openSafeDatabase(fd: FileDescriptor, cacheDir: File?, extension: String): DatabaseSessionLocal {
        return openSafeDatabase(fd, cacheDir, extension, null)
    }

    override fun openSafeDatabase(fd: FileDescriptor, cacheDir: File?, extension: String, password: String?): DatabaseSessionLocal {
        val cleanExt = extension.lowercase(Locale.ROOT).trim()
        val header = ByteBuffer.allocateDirect(4096)
        val fileSize = try { Os.fstat(fd).st_size } catch (_: Throwable) { -1L }
        val r = try { Os.pread(fd, header, 0L) } catch (_: Throwable) { 0 }
        header.flip()

        if (r >= 20) {
            val arr = ByteArray(r)
            header.get(arr)
            header.position(0)
            if (arr[0].toInt() == 0 && arr[1].toInt() == 1 && arr[2].toInt() == 0 && arr[3].toInt() == 0) {
                val magic = String(arr, 4, 15, StandardCharsets.US_ASCII)
                if (magic.startsWith("Standard Jet DB") || magic.startsWith("Standard ACE DB")) {
                    val dup = ParcelFileDescriptor.dup(fd)
                    return GplCoreEngineBridgeLoader.get()?.createAccessSession(dup, password)
                        ?: UnavailableGplDatabaseSession(dup, "Microsoft Access (.mdb/.accdb)")
                }
            }
            if (r >= 16 && String(arr, 0, 15, StandardCharsets.US_ASCII) == "SQLite format 3") {
                return SqliteDatabaseSession(ParcelFileDescriptor.dup(fd), cacheDir)
            }
            if (arr[0] == 'P'.code.toByte() && arr[1] == 'A'.code.toByte() &&
                arr[2] == 'R'.code.toByte() && (arr[3] == '1'.code.toByte() || arr[3] == 'E'.code.toByte())
            ) {
                return CleanRoomParquetAndNoSqlDatabaseSession(ParcelFileDescriptor.dup(fd), "parquet")
            }
            if (CleanRoomDuckDbReader.isDuckDbHeader(arr)) {
                return CleanRoomParquetAndNoSqlDatabaseSession(ParcelFileDescriptor.dup(fd), "duckdb")
            }
            if (CleanRoomRealmReader.isRealmHeader(arr)) {
                return CleanRoomParquetAndNoSqlDatabaseSession(ParcelFileDescriptor.dup(fd), "realm")
            }
        }

        val format = FileSignatureDetector.resolveFormat(fd, header, cleanExt, fileSize)
        if (format == DetectedFormat.DBF || cleanExt == "dbf") {
            return CleanRoomDbfDatabaseSession(ParcelFileDescriptor.dup(fd))
        }
        if (format == DetectedFormat.PARQUET || cleanExt in setOf("parquet", "duckdb", "realm", "bson", "jsonl", "ndjson")) {
            return CleanRoomParquetAndNoSqlDatabaseSession(
                ParcelFileDescriptor.dup(fd),
                cleanExt.ifEmpty { "parquet" }
            )
        }

        val pfd = ParcelFileDescriptor.dup(fd)
        return SqliteDatabaseSession(pfd, cacheDir)
    }
}

/*
 * Shared base class for in-memory Clean-Room tabular database sessions (Access, DBF, Parquet, BSON, JSONL, DuckDB, Realm).
 */
abstract class BaseInMemoryTableDatabaseSession(
    protected val pfd: ParcelFileDescriptor
) : DatabaseSessionLocal {

    data class InMemoryTable(
        val name: String,
        val columnNames: List<String>,
        val rows: List<List<String>>
    )

    @Volatile protected var isClosed = false
    @Volatile protected var lastErrorMsg: String? = null
    @Volatile protected var activeTableIdx = 0
    override val isSchemaTrustedStatus: Boolean = false

    protected val tables = mutableListOf<InMemoryTable>()
    protected val source = ZeroCacheReadableSource(pfd.fileDescriptor)

    override val pageCount: Int = 1
    override fun getLastError(): String? = lastErrorMsg
    override fun getSheetNames(): List<String> = if (tables.isEmpty()) listOf("Empty DB") else tables.map { it.name }
    override fun getActiveSheetIndex(): Int = activeTableIdx.coerceIn(0, (tables.size - 1).coerceAtLeast(0))

    override fun setActiveSheetIndex(index: Int) {
        if (index in tables.indices) activeTableIdx = index
    }

    override fun fetchVisibleRows(offsetIndex: Long, limit: Int): List<List<String>> {
        val tbl = tables.getOrNull(getActiveSheetIndex()) ?: return emptyList()
        val start = offsetIndex.coerceAtLeast(0L).toInt()
        if (start >= tbl.rows.size || limit <= 0) return emptyList()
        val end = minOf(tbl.rows.size, start + limit)
        return tbl.rows.subList(start, end)
    }

    override fun getCellText(row: Int, col: Int): String {
        val tbl = tables.getOrNull(getActiveSheetIndex()) ?: return ""
        return tbl.rows.getOrNull(row)?.getOrNull(col) ?: ""
    }

    override fun getSelectedRangeText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        val tbl = tables.getOrNull(getActiveSheetIndex()) ?: return ""
        if (tbl.rows.isEmpty() || tbl.columnNames.isEmpty()) return ""
        val rMin = min(startRow, endRow).coerceAtLeast(0)
        val rMax = max(startRow, endRow).coerceAtMost(tbl.rows.size - 1)
        val cMin = min(startCol, endCol).coerceAtLeast(0)
        val cMax = max(startCol, endCol).coerceAtMost(tbl.columnNames.size - 1)
        if (rMin > rMax || cMin > cMax) return ""

        val sb = StringBuilder()
        for (r in rMin..rMax) {
            val row = tbl.rows[r]
            for (c in cMin..cMax) {
                if (c > cMin) sb.append('\t')
                sb.append(row.getOrNull(c) ?: "")
            }
            if (r < rMax) sb.append('\n')
        }
        return sb.toString()
    }

    override fun getPageText(pageIndex: Int): String {
        val cleanIdx = pageIndex and LIGHT_THEME_PAGE_FLAG.inv()
        val targetIdx = if (cleanIdx in tables.indices) cleanIdx else getActiveSheetIndex()
        val tbl = tables.getOrNull(targetIdx) ?: return ""
        return tbl.rows.joinToString("\n") { it.joinToString("\t") }
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank() || isClosed) return -1L
        for (i in startPage.coerceAtLeast(0) until tables.size) {
            if (tables[i].rows.any { row -> row.any { it.contains(query, ignoreCase = true) } } ||
                tables[i].columnNames.any { it.contains(query, ignoreCase = true) }
            ) {
                return i.toLong()
            }
        }
        return -1L
    }

    override fun getDocumentWidth(): Float {
        val tbl = tables.getOrNull(getActiveSheetIndex()) ?: return 0f
        if (tbl.columnNames.isEmpty()) return 0f
        return 84f + tbl.columnNames.size * 170f
    }

    override fun getDocumentHeight(): Float {
        val tbl = tables.getOrNull(getActiveSheetIndex()) ?: return 0f
        if (tbl.rows.isEmpty()) return 56f + 48f
        return 56f + tbl.rows.size * 48f
    }

    @Synchronized
    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLightTheme) LIGHT_SHEET_BG_COLOR else DARK_SHEET_BG_COLOR)

        val tbl = tables.getOrNull(getActiveSheetIndex()) ?: return
        val cols = tbl.columnNames
        if (cols.isEmpty()) return

        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 4f)
        val headerHeight = 56f * zoom
        val headerWidth = 84f * zoom
        val cellHeight = 48f * zoom
        val dataColCount = cols.size
        val totalRows = tbl.rows.size

        val unzoomedAvailGridW = (bitmap.width.toFloat() - 84f).coerceAtLeast(170f)
        val baseUnzoomedCellW = max(170f, unzoomedAvailGridW / dataColCount)
        val cellWidth = baseUnzoomedCellW * zoom

        val totalTableW = headerWidth + dataColCount * cellWidth
        val totalTableH = headerHeight + totalRows * cellHeight
        val maxEffX = (totalTableW - bitmap.width.toFloat()).coerceAtLeast(0f)
        val maxEffY = (totalTableH - bitmap.height.toFloat()).coerceAtLeast(0f)
        val effX = tileX.toFloat().coerceIn(0f, maxEffX)
        val effY = tileY.toFloat().coerceIn(0f, maxEffY)

        val paintCellBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_CELL_BG_COLOR else DARK_SHEET_CELL_BG_COLOR
            style = Paint.Style.FILL
        }
        val paintText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_TEXT_COLOR else DARK_SHEET_TEXT_COLOR
            textSize = 26f * zoom
        }
        val paintHeader = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_HEADER_TEXT_COLOR else DARK_SHEET_HEADER_TEXT_COLOR
            textSize = 26f * zoom
            isFakeBoldText = true
        }
        val paintHeaderBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_HEADER_BG_COLOR else DARK_SHEET_HEADER_BG_COLOR
            style = Paint.Style.FILL
        }
        val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_GRID_COLOR else DARK_SHEET_GRID_COLOR
            strokeWidth = 2f
        }

        val startCol = (effX / cellWidth).toInt().coerceAtLeast(0)
        val endCol = (startCol + (bitmap.width / cellWidth).toInt() + 2).coerceAtMost(cols.size - 1)
        val startRow = (effY / cellHeight).toInt().coerceAtLeast(0)
        val endRow = (startRow + (bitmap.height / cellHeight).toInt() + 2).coerceAtMost((totalRows - 1).coerceAtLeast(-1))
        val subY = effY % cellHeight

        val tableRightX = (headerWidth + (endCol + 1) * cellWidth - effX).coerceIn(headerWidth, bitmap.width.toFloat())
        val tableBottomY = if (endRow >= startRow) {
            (headerHeight + (endRow - startRow + 1) * cellHeight - subY).coerceIn(headerHeight, bitmap.height.toFloat())
        } else headerHeight

        if (tableBottomY > headerHeight) {
            canvas.drawRect(headerWidth, headerHeight, tableRightX, tableBottomY, paintCellBg)
        }

        if (endRow >= startRow) {
            for (r in startRow..endRow) {
                val rowY = headerHeight + (r - startRow) * cellHeight - subY
                val rowVals = tbl.rows[r]
                for (c in startCol..endCol) {
                    val colX = headerWidth + c * cellWidth - effX
                    val txt = rowVals.getOrNull(c) ?: ""
                    if (txt.isNotEmpty()) {
                        val clipped = TextUtils.ellipsize(
                            txt, paintText, (cellWidth - 16f * zoom).coerceAtLeast(10f), TextUtils.TruncateAt.END
                        ).toString()
                        canvas.save()
                        canvas.clipRect(
                            maxOf(headerWidth, colX + 2f),
                            maxOf(headerHeight, rowY + 2f),
                            colX + cellWidth - 2f,
                            rowY + cellHeight - 2f
                        )
                        canvas.drawText(clipped, colX + 10f * zoom, rowY + 33f * zoom, paintText)
                        canvas.restore()
                    }
                    canvas.drawLine(colX + cellWidth, maxOf(headerHeight, rowY), colX + cellWidth, rowY + cellHeight, paintGrid)
                }
                canvas.drawLine(headerWidth, rowY + cellHeight, tableRightX, rowY + cellHeight, paintGrid)
            }
        }

        canvas.drawRect(0f, 0f, tableRightX, headerHeight, paintHeaderBg)
        for (c in startCol..endCol) {
            val colX = headerWidth + c * cellWidth - effX
            val clippedCol = TextUtils.ellipsize(
                cols[c], paintHeader, (cellWidth - 16f * zoom).coerceAtLeast(10f), TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(clippedCol, colX + 10f * zoom, 36f * zoom, paintHeader)
            canvas.drawLine(colX + cellWidth, 0f, colX + cellWidth, headerHeight, paintGrid)
        }
        canvas.drawLine(0f, headerHeight, tableRightX, headerHeight, paintGrid)

        if (endRow >= startRow) {
            for (r in startRow..endRow) {
                val rowY = headerHeight + (r - startRow) * cellHeight - subY
                val clampedTop = maxOf(headerHeight, rowY)
                canvas.drawRect(0f, clampedTop, headerWidth, rowY + cellHeight, paintHeaderBg)
                canvas.drawText("${r + 1}", 14f * zoom, rowY + 33f * zoom, paintHeader)
                canvas.drawLine(0f, rowY + cellHeight, headerWidth, rowY + cellHeight, paintGrid)
            }
        }
        canvas.drawRect(0f, 0f, headerWidth, headerHeight, paintHeaderBg)
        canvas.drawLine(headerWidth, 0f, headerWidth, tableBottomY, paintGrid)
        canvas.drawLine(0f, headerHeight, headerWidth, headerHeight, paintGrid)
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        source.close()
        try { pfd.close() } catch (_: Exception) {}
    }
}

/*
 * Clean-Room read-only inspector for dBase III / IV / V & Visual FoxPro (.dbf) tables.
 */
class CleanRoomDbfDatabaseSession(
    pfd: ParcelFileDescriptor
) : BaseInMemoryTableDatabaseSession(pfd) {

    private data class DbfFieldDesc(
        val name: String,
        val type: Char,
        val length: Int,
        val decimalCount: Int
    )

    init {
        parseDbfTable()
    }

    private fun resolveDbfCharset(ldid: Int, sampleBytes: ByteArray): Charset {
        return when (ldid) {
            0x26, 0x65 -> try { Charset.forName("CP866") } catch (_: Throwable) { PlainTextSession.CP1251 }
            0x57, 0xC9 -> PlainTextSession.CP1251
            0x01, 0x02, 0x03 -> StandardCharsets.ISO_8859_1
            else -> {
                val utf8Dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                try {
                    utf8Dec.decode(ByteBuffer.wrap(sampleBytes))
                    StandardCharsets.UTF_8
                } catch (_: Throwable) {
                    PlainTextSession.CP1251
                }
            }
        }
    }

    private fun parseDbfTable() {
        try {
            val fileSize = minOf(source.size, 64L * 1024L * 1024L).toInt()
            if (fileSize < 32) {
                lastErrorMsg = "dBase (.dbf) file is empty or truncated (< 32 bytes)."
                return
            }
            val buf = ByteBuffer.allocateDirect(fileSize).order(ByteOrder.LITTLE_ENDIAN)
            val got = source.readAt(0L, buf)
            if (got < 32) {
                lastErrorMsg = "Unable to read dBase (.dbf) header."
                return
            }
            buf.flip()
            val bytes = ByteArray(got)
            buf.get(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val numRecords = (bb.getInt(4).toLong() and 0xFFFFFFFFL).coerceAtMost(25_000L).toInt()
            val headerLen = bb.getShort(8).toInt() and 0xFFFF
            val recordLen = bb.getShort(10).toInt() and 0xFFFF
            val ldid = bytes[29].toInt() and 0xFF

            if (headerLen < 32 || recordLen < 1 || headerLen > bytes.size) {
                lastErrorMsg = "Corrupted dBase (.dbf) table header."
                return
            }

            val charset = resolveDbfCharset(ldid, bytes.copyOfRange(minOf(headerLen, bytes.size), minOf(bytes.size, headerLen + 4096)))

            val fields = ArrayList<DbfFieldDesc>()
            var pos = 32
            while (pos + 32 <= headerLen && pos + 32 <= bytes.size) {
                val term = bytes[pos].toInt() and 0xFF
                if (term == 0x0D || term == 0x00) break
                var nameEnd = pos
                while (nameEnd < pos + 11 && bytes[nameEnd].toInt() != 0) nameEnd++
                val fieldName = String(bytes, pos, nameEnd - pos, charset).trim().ifEmpty { "FIELD_${fields.size + 1}" }
                val fieldType = (bytes[pos + 11].toInt() and 0xFF).toChar()
                val fieldLen = bytes[pos + 16].toInt() and 0xFF
                val decCount = bytes[pos + 17].toInt() and 0xFF
                if (fieldLen > 0) {
                    fields.add(DbfFieldDesc(fieldName, fieldType, fieldLen, decCount))
                }
                pos += 32
            }

            if (fields.isEmpty()) {
                lastErrorMsg = "dBase (.dbf) file contains no valid field descriptors."
                return
            }

            val rows = ArrayList<List<String>>(minOf(numRecords, 15_000))
            var recPos = headerLen
            for (r in 0 until numRecords) {
                if (recPos + recordLen > bytes.size || rows.size >= 15_000) break
                val delFlag = bytes[recPos].toInt() and 0xFF
                if (delFlag != 0x2A) {
                    val rowCells = ArrayList<String>(fields.size)
                    var fldOffset = recPos + 1
                    for (fld in fields) {
                        if (fldOffset + fld.length > recPos + recordLen || fldOffset + fld.length > bytes.size) {
                            rowCells.add("")
                            break
                        }
                        val formatted = decodeDbfField(bytes, bb, fldOffset, fld, charset)
                        rowCells.add(formatted)
                        fldOffset += fld.length
                    }
                    rows.add(rowCells)
                }
                recPos += recordLen
            }

            tables.add(
                InMemoryTable(
                    name = "DBF Table (${rows.size} rows)",
                    columnNames = fields.map { "${it.name} (${it.type})" },
                    rows = rows
                )
            )
        } catch (e: Exception) {
            lastErrorMsg = "dBase (.dbf) read error: ${e.localizedMessage}"
        }
    }

    private fun decodeDbfField(
        bytes: ByteArray,
        bb: ByteBuffer,
        offset: Int,
        fld: DbfFieldDesc,
        charset: Charset
    ): String {
        return when (fld.type.uppercaseChar()) {
            'I' -> if (fld.length == 4 && offset + 4 <= bytes.size) bb.getInt(offset).toString() else ""
            'B' -> if (fld.length == 8 && offset + 8 <= bytes.size) bb.getDouble(offset).toString() else ""
            'Y' -> if (fld.length == 8 && offset + 8 <= bytes.size) String.format(Locale.US, "%.4f", bb.getLong(offset) / 10000.0) else ""
            'D' -> {
                val raw = String(bytes, offset, fld.length, StandardCharsets.US_ASCII).trim()
                if (raw.length == 8 && raw.all { it.isDigit() }) {
                    "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
                } else raw
            }
            'L' -> {
                val ch = (bytes[offset].toInt() and 0xFF).toChar().uppercaseChar()
                when (ch) {
                    'T', 'Y', '1' -> "TRUE"
                    'F', 'N', '0' -> "FALSE"
                    else -> ""
                }
            }
            else -> String(bytes, offset, fld.length, charset).replace('\u0000', ' ').trim()
        }
    }
}

/*
 * Clean-Room read-only tabular inspector for Apache Parquet (PAR1), JSON Lines (.jsonl / .ndjson),
 * MongoDB Binary JSON (.bson), DuckDB (DUCK) & Realm (T-DB).
 */
class CleanRoomParquetAndNoSqlDatabaseSession(
    pfd: ParcelFileDescriptor,
    private val formatHint: String
) : BaseInMemoryTableDatabaseSession(pfd) {

    init {
        parseContainer()
    }

    private fun parseContainer() {
        try {
            val fileSize = source.size
            if (fileSize <= 0L) {
                lastErrorMsg = "Database file (${formatHint.uppercase(Locale.ROOT)}) is empty (0 bytes)."
                return
            }
            val maxRead = if (formatHint == "parquet") minOf(fileSize, 64L).toInt() else minOf(fileSize, 32L * 1024L * 1024L).toInt()
            val buf = ByteBuffer.allocateDirect(maxRead)
            val got = source.readAt(0L, buf)
            if (got <= 0) {
                lastErrorMsg = "Unable to read database file."
                return
            }
            buf.flip()
            val bytes = ByteArray(got)
            buf.get(bytes)

            val headMagic = if (got >= 4) String(bytes, 0, 4, StandardCharsets.US_ASCII) else ""
            if (headMagic == "PARE") {
                lastErrorMsg = "UNSUPPORTED_ENCRYPTION: Password decryption for encrypted Apache Parquet files is not supported in this version."
                return
            }
            val isParquet = headMagic == "PAR1" || formatHint == "parquet"
            val isDuckDb = CleanRoomDuckDbReader.isDuckDbHeader(bytes) || formatHint == "duckdb"
            val isRealm = CleanRoomRealmReader.isRealmHeader(bytes) || formatHint == "realm"
            val isBson = formatHint == "bson" || (!isParquet && !isDuckDb && !isRealm && isLikelyBsonDocument(bytes))

            when {
                isParquet -> parseParquetFile(bytes, fileSize)
                isBson -> parseBsonDocuments(bytes)
                isDuckDb -> parseDuckDb(bytes)
                isRealm -> parseRealm(bytes)
                else -> parseJsonLines(bytes)
            }
        } catch (uoe: UnsupportedOperationException) {
            val msg = uoe.message.orEmpty()
            lastErrorMsg = if (msg.contains("encrypted", ignoreCase = true)) {
                "UNSUPPORTED_ENCRYPTION: Password decryption for encrypted ${formatHint.uppercase(Locale.ROOT)} files is not supported in this version."
            } else {
                "Database parse error (${formatHint.uppercase(Locale.ROOT)}): $msg"
            }
        } catch (e: Exception) {
            lastErrorMsg = "Database parse error (${formatHint.uppercase(Locale.ROOT)}): ${e.localizedMessage}"
        }
    }

    private fun isLikelyBsonDocument(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        val firstTrimmed = bytes.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() }
        if (firstTrimmed == '{'.code.toByte() || firstTrimmed == '['.code.toByte()) return false
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val docLen = bb.getInt(0)
        return docLen in 5..bytes.size && bytes[docLen - 1].toInt() == 0
    }

    private fun parseJsonLines(bytes: ByteArray) {
        val columnsSet = LinkedHashSet<String>()
        val parsedRecords = ArrayList<LinkedHashMap<String, String>>()

        BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null && parsedRecords.size < 10_000) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    try {
                        if (trimmed.startsWith("{")) {
                            val obj = JSONObject(trimmed)
                            val rowMap = LinkedHashMap<String, String>()
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                columnsSet.add(k)
                                val v = obj.opt(k)
                                rowMap[k] = if (v == null || v === JSONObject.NULL) "NULL" else v.toString()
                            }
                            if (rowMap.isNotEmpty()) parsedRecords.add(rowMap)
                        } else if (trimmed.startsWith("[")) {
                            val arr = JSONArray(trimmed)
                            for (i in 0 until minOf(arr.length(), 10_000 - parsedRecords.size)) {
                                val elem = arr.optJSONObject(i)
                                if (elem != null) {
                                    val rowMap = LinkedHashMap<String, String>()
                                    val keys = elem.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        columnsSet.add(k)
                                        val v = elem.opt(k)
                                        rowMap[k] = if (v == null || v === JSONObject.NULL) "NULL" else v.toString()
                                    }
                                    if (rowMap.isNotEmpty()) parsedRecords.add(rowMap)
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                line = reader.readLine()
            }
        }

        if (columnsSet.isEmpty() || parsedRecords.isEmpty()) {
            lastErrorMsg = "JSONL/NDJSON file contains no valid JSON object records."
            return
        }

        val colList = columnsSet.toList()
        val rows = parsedRecords.map { rec -> colList.map { col -> rec[col] ?: "" } }
        tables.add(InMemoryTable("JSONL Records (${rows.size})", colList, rows))
    }

    private fun parseBsonDocuments(bytes: ByteArray) {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val columnsSet = LinkedHashSet<String>()
        val records = ArrayList<LinkedHashMap<String, String>>()
        val isoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        var docStart = 0
        while (docStart + 5 <= bytes.size && records.size < 10_000) {
            val docLen = bb.getInt(docStart)
            if (docLen < 5 || docStart + docLen > bytes.size || bytes[docStart + docLen - 1].toInt() != 0) break

            val rowMap = LinkedHashMap<String, String>()
            var pos = docStart + 4
            val docEnd = docStart + docLen - 1

            while (pos < docEnd) {
                val elemType = bytes[pos++].toInt() and 0xFF
                if (elemType == 0) break
                val nameStart = pos
                while (pos < docEnd && bytes[pos].toInt() != 0) pos++
                if (pos >= docEnd) break
                val keyName = String(bytes, nameStart, pos - nameStart, StandardCharsets.UTF_8)
                pos++

                val valueStr: String = when (elemType) {
                    0x01 -> {
                        if (pos + 8 > docEnd) break
                        val d = bb.getDouble(pos)
                        pos += 8
                        d.toString()
                    }
                    0x02, 0x0D, 0x0E -> {
                        if (pos + 4 > docEnd) break
                        val sLen = bb.getInt(pos)
                        pos += 4
                        if (sLen < 1 || pos + sLen > docEnd) break
                        val s = String(bytes, pos, sLen - 1, StandardCharsets.UTF_8)
                        pos += sLen
                        s
                    }
                    0x03, 0x04 -> {
                        if (pos + 4 > docEnd) break
                        val subLen = bb.getInt(pos)
                        if (subLen < 5 || pos + subLen > docEnd) break
                        pos += subLen
                        if (elemType == 0x03) "{Object}" else "[Array]"
                    }
                    0x05 -> {
                        if (pos + 5 > docEnd) break
                        val bLen = bb.getInt(pos)
                        pos += 5 + bLen.coerceAtLeast(0)
                        "[BLOB $bLen B]"
                    }
                    0x07 -> {
                        if (pos + 12 > docEnd) break
                        val hexSb = StringBuilder(24)
                        for (k in 0 until 12) {
                            hexSb.append(String.format(Locale.US, "%02x", bytes[pos + k].toInt() and 0xFF))
                        }
                        pos += 12
                        hexSb.toString()
                    }
                    0x08 -> {
                        if (pos + 1 > docEnd) break
                        val b = bytes[pos++].toInt() != 0
                        if (b) "true" else "false"
                    }
                    0x09 -> {
                        if (pos + 8 > docEnd) break
                        val ms = bb.getLong(pos)
                        pos += 8
                        isoFmt.format(Date(ms))
                    }
                    0x0A -> "NULL"
                    0x10 -> {
                        if (pos + 4 > docEnd) break
                        val v = bb.getInt(pos)
                        pos += 4
                        v.toString()
                    }
                    0x11, 0x12 -> {
                        if (pos + 8 > docEnd) break
                        val v = bb.getLong(pos)
                        pos += 8
                        v.toString()
                    }
                    else -> break
                }

                if (keyName.isNotEmpty()) {
                    columnsSet.add(keyName)
                    rowMap[keyName] = valueStr
                }
            }

            if (rowMap.isNotEmpty()) {
                records.add(rowMap)
            }
            docStart += docLen
        }

        if (columnsSet.isEmpty() || records.isEmpty()) {
            lastErrorMsg = "BSON file is empty or contains no valid BSON documents."
            return
        }

        val colList = columnsSet.toList()
        val rows = records.map { rec -> colList.map { c -> rec[c] ?: "" } }
        tables.add(InMemoryTable("BSON Collection (${rows.size})", colList, rows))
    }

    private class ParquetRowList(private val reader: CleanRoomParquetReader) : AbstractList<List<String>>() {
        override val size: Int = reader.numRows.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        override fun get(index: Int): List<String> {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Row $index")
            return try {
                reader.row(index.toLong())
            } catch (e: Exception) {
                reader.leaves.map { "⚠ ${e.message ?: "decode error"}" }
            }
        }
    }

    private fun parseParquetFile(@Suppress("UNUSED_PARAMETER") headBytes: ByteArray, totalFileSize: Long) {
        val reader = CleanRoomParquetReader(ParquetByteSource { offset, length ->
            if (length < 0 || offset < 0 || offset + length > totalFileSize) throw CorruptedDataException("Read beyond end of Parquet file")
            val buf = ByteBuffer.allocateDirect(length)
            var done = 0
            while (done < length) {
                val r = source.readAt(offset + done, buf)
                if (r <= 0) break
                done += r
            }
            if (done != length) throw CorruptedDataException("Unexpected end of Parquet file")
            buf.flip()
            ByteArray(length).also { buf.get(it) }
        }, totalFileSize)
        parquetReader = reader
        val columnNames = reader.leaves.map { it.displayName }
        tables.add(InMemoryTable("Parquet Data (${reader.numRows} rows)", columnNames, ParquetRowList(reader)))
        val schemaRows = reader.leaves.mapIndexed { idx, leaf ->
            listOf((idx + 1).toString(), leaf.path.joinToString("."), reader.physicalTypeName(leaf), "def ${leaf.maxDefinition} / rep ${leaf.maxRepetition}")
        }
        tables.add(
            InMemoryTable(
                name = "Parquet Schema (${columnNames.size} cols, ${reader.rowGroups.size} row groups)",
                columnNames = listOf("#", "Column", "Type", "Levels"),
                rows = schemaRows
            )
        )
    }

    private var parquetReader: CleanRoomParquetReader? = null

    override fun getPageText(pageIndex: Int): String {
        if (parquetReader == null) return super.getPageText(pageIndex)
        val cleanIdx = pageIndex and LIGHT_THEME_PAGE_FLAG.inv()
        val targetIdx = if (cleanIdx in tables.indices) cleanIdx else getActiveSheetIndex()
        val tbl = tables.getOrNull(targetIdx) ?: return ""
        val sb = StringBuilder(tbl.columnNames.joinToString("\t"))
        for (i in 0 until minOf(tbl.rows.size, 2000)) sb.append('\n').append(tbl.rows[i].joinToString("\t"))
        return sb.toString()
    }

    private fun parseDuckDb(bytes: ByteArray) {
        if (CleanRoomDuckDbReader.isEncrypted(bytes)) {
            lastErrorMsg = "UNSUPPORTED_ENCRYPTION: Password decryption for encrypted DuckDB databases is not supported in this version."
            return
        }
        val parsed = CleanRoomDuckDbReader.parse(bytes)
        if (parsed == null || parsed.tables.isEmpty()) {
            lastErrorMsg = "DuckDB database header or catalog is invalid."
            return
        }
        for (t in parsed.tables) {
            tables.add(InMemoryTable(t.name, t.columns, t.rows))
        }
    }

    private fun parseRealm(bytes: ByteArray) {
        val parsed = CleanRoomRealmReader.parse(bytes)
        if (parsed == null || parsed.tables.isEmpty()) {
            lastErrorMsg = if (bytes.size >= 4096 && !CleanRoomRealmReader.isRealmHeader(bytes)) {
                "UNSUPPORTED_ENCRYPTION: Password decryption for encrypted Realm databases is not supported in this version."
            } else {
                "Realm database header or group structure is invalid."
            }
            return
        }
        for (t in parsed.tables) {
            tables.add(InMemoryTable(t.name, t.columns, t.rows))
        }
    }
}

/*
 * Clean-Room read-only inspector for Microsoft Access Jet 3.x / Jet 4.0 (.mdb) and ACE 12/14/16 (.accdb)
 * databases without using closed Microsoft ACE drivers.
 */
private class UnavailableGplDatabaseSession(
    pfd: ParcelFileDescriptor,
    formatName: String
) : BaseInMemoryTableDatabaseSession(pfd) {
    init {
        lastErrorMsg = "$formatName engine is not included in this build."
    }

    override fun getPageText(pageIndex: Int): String = ""
}

object SqliteFieldType {
    const val NULL = 0
    const val INTEGER = 1
    const val FLOAT = 2
    const val STRING = 3
    const val BLOB = 4
}

interface SqliteCursor : AutoCloseable {
    fun moveToNext(): Boolean
    fun moveToFirst(): Boolean
    val columnCount: Int
    val columnNames: Array<String>
    fun getColumnIndex(name: String): Int
    fun getType(colIdx: Int): Int
    fun getString(colIdx: Int): String?
    fun getLong(colIdx: Int): Long
    fun getDouble(colIdx: Int): Double
    fun getBlob(colIdx: Int): ByteArray?
}

interface SqliteDbConnection : AutoCloseable {
    fun execSQL(sql: String)
    fun query(sql: String, args: Array<String>? = null): SqliteCursor
}

class AndroidSqliteCursor(private val cursor: Cursor) : SqliteCursor {
    override fun moveToNext(): Boolean = cursor.moveToNext()
    override fun moveToFirst(): Boolean = cursor.moveToFirst()
    override val columnCount: Int get() = cursor.columnCount
    override val columnNames: Array<String> get() = cursor.columnNames ?: emptyArray()
    override fun getColumnIndex(name: String): Int = cursor.getColumnIndex(name)
    override fun getType(colIdx: Int): Int = cursor.getType(colIdx)
    override fun getString(colIdx: Int): String? = cursor.getString(colIdx)
    override fun getLong(colIdx: Int): Long = cursor.getLong(colIdx)
    override fun getDouble(colIdx: Int): Double = cursor.getDouble(colIdx)
    override fun getBlob(colIdx: Int): ByteArray? = cursor.getBlob(colIdx)
    override fun close() {
        try { cursor.close() } catch (_: Exception) {}
    }
}

class AndroidSqliteConnection(private val db: SQLiteDatabase) : SqliteDbConnection {
    override fun execSQL(sql: String) {
        db.execSQL(sql)
    }

    override fun query(sql: String, args: Array<String>?): SqliteCursor {
        val cursor = db.rawQuery(sql, args)
        return AndroidSqliteCursor(cursor)
    }

    override fun close() {
        try { db.close() } catch (_: Exception) {}
    }
}

enum class SqliteSessionStatus {
    LOADING_META,
    EMPTY_DB,
    READY,
    ERROR,
    CLOSED
}

internal data class TableMeta(
    val name: String,
    val isView: Boolean,
    val columns: List<String>,
    val rowCount: Long
)

internal data class SessionStateSnapshot(
    val status: SqliteSessionStatus,
    val tableIndex: Int,
    val token: Long,
    val stateVersion: Long,
    val meta: TableMeta?,
    val errorMsg: String?,
    val connection: SqliteDbConnection?
)

class SqliteDatabaseSession(
    private val pfd: ParcelFileDescriptor? = null,
    private val cacheDir: File? = null,
    providedConnection: SqliteDbConnection? = null
) : DatabaseSessionLocal {

    private val stateLock = Object()
    @Volatile private var isClosed = false
    private var inFlightOperations = 0
    private var dbConn: SqliteDbConnection? = null
    private var activeTempFile: File? = null

    private val tables = mutableListOf<String>()
    @Volatile private var activeTableIdx = 0
    @Volatile private var activeTableMeta: TableMeta? = null
    @Volatile private var isLoadingMeta = false
    @Volatile private var activeTableToken = 0L
    @Volatile private var stateVersionCounter = 1L

    @Volatile var lastErrorMsg: String? = null
        private set

    @Volatile var onMetaLoadedListener: Runnable? = null

    private val metadataExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sqlite-meta-loader").apply { isDaemon = true }
    }

    override val isSchemaTrustedStatus: Boolean = false

    constructor(pfd: ParcelFileDescriptor, cacheDir: File?) : this(pfd, cacheDir, null)

    private fun resolveEffectiveCacheDir(): File? {
        if (cacheDir != null && (cacheDir.exists() || cacheDir.mkdirs())) return cacheDir
        val candidates = listOfNotNull(
            System.getProperty("omnireader.cache.dir")?.let { File(it) },
            System.getProperty("java.io.tmpdir")?.let { File(it) },
            File("/data/data/me.hlebkukleuski.fullfilestudio/cache"),
            File("/data/data/me.hlebkukleuski.fullfilestudio.sitedl/cache")
        )
        return candidates.firstOrNull { dir ->
            try {
                if (!dir.exists()) dir.mkdirs()
                dir.exists() && dir.canWrite()
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun applySecurityPragmasAndReadTables(conn: SqliteDbConnection) {
        try {
            conn.execSQL("PRAGMA trusted_schema = OFF;")
            conn.execSQL("PRAGMA cell_size_check = ON;")
        } catch (_: Exception) {}

        tables.clear()
        conn.query(
            "SELECT name FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT GLOB 'sqlite_*' AND name != 'android_metadata' ORDER BY type ASC, name ASC;"
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (!name.isNullOrEmpty()) {
                    tables.add(name)
                }
            }
        }
    }

    init {
        try {
            if (providedConnection != null) {
                try {
                    applySecurityPragmasAndReadTables(providedConnection)
                    dbConn = providedConnection
                } catch (e: Throwable) {
                    try { providedConnection.close() } catch (_: Throwable) {}
                    throw IllegalArgumentException("Failed to read SQLite schema: ${e.message}", e)
                }
            } else if (pfd != null) {
                var openedConn: SqliteDbConnection? = null
                var directOpenError: Throwable? = null

                try {
                    val fdPath = File("/proc/self/fd/${pfd.fd}")
                    if (fdPath.exists()) {
                        val params = OpenParams.Builder()
                            .setOpenFlags(SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                            .build()
                        val rawDb = SQLiteDatabase.openDatabase(fdPath, params)
                        val candidate = AndroidSqliteConnection(rawDb)
                        try {
                            applySecurityPragmasAndReadTables(candidate)
                            openedConn = candidate
                        } catch (schemaErr: Throwable) {
                            candidate.close()
                            throw schemaErr
                        }
                    }
                } catch (e: Throwable) {
                    directOpenError = e
                }

                if (openedConn == null && android.os.Process.isIsolated()) {
                    try {
                        val size = android.system.Os.fstat(pfd.fileDescriptor).st_size
                        val reader = CleanRoomSqliteReader(SqlitePageSource { offset, dst, length ->
                            android.system.Os.pread(pfd.fileDescriptor, dst, 0, length, offset)
                        }, size)
                        val candidate = CleanRoomSqliteConnection(reader)
                        applySecurityPragmasAndReadTables(candidate)
                        openedConn = candidate
                    } catch (e: Throwable) {
                        if (directOpenError != null) e.addSuppressed(directOpenError)
                        directOpenError = e
                    }
                }

                if (openedConn == null) {
                    var tempFile: File? = null
                    try {
                        tempFile = com.omnireader.core.engine.ScratchFiles.createTempFile("sqlite_inspect_", ".db", resolveEffectiveCacheDir())
                        copyDescriptorViaPread(pfd.fileDescriptor, tempFile)

                        val params = OpenParams.Builder()
                            .setOpenFlags(SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                            .build()
                        val rawDb = SQLiteDatabase.openDatabase(tempFile, params)
                        val candidate = AndroidSqliteConnection(rawDb)
                        try {
                            applySecurityPragmasAndReadTables(candidate)
                            openedConn = candidate
                            activeTempFile = tempFile
                        } catch (schemaErr: Throwable) {
                            candidate.close()
                            throw schemaErr
                        }
                    } catch (fallbackErr: Throwable) {
                        com.omnireader.core.engine.ScratchFiles.delete(tempFile)
                        if (directOpenError != null) {
                            fallbackErr.addSuppressed(directOpenError)
                        }
                        throw IllegalArgumentException("Failed to open SQLite database: ${fallbackErr.message}", fallbackErr)
                    }
                }

                if (openedConn == null) {
                    throw IllegalArgumentException(
                        "Failed to open SQLite database: ${directOpenError?.message ?: "Unable to open descriptor"}",
                        directOpenError
                    )
                }
                dbConn = openedConn
            } else {
                throw IllegalArgumentException("Failed to open SQLite database: Neither connection nor PFD was provided.")
            }

            if (tables.isNotEmpty()) {
                scheduleTableMetaLoad(0)
            } else {
                synchronized(stateLock) {
                    isLoadingMeta = false
                    stateVersionCounter++
                    stateLock.notifyAll()
                }
            }
        } catch (t: Throwable) {
            val initError = if (t is IllegalArgumentException) t else IllegalArgumentException("Failed to open SQLite database: ${t.message}", t)
            synchronized(stateLock) {
                isClosed = true
                stateLock.notifyAll()
            }
            try { dbConn?.close() } catch (s: Throwable) { initError.addSuppressed(s) }
            dbConn = null
            com.omnireader.core.engine.ScratchFiles.delete(activeTempFile)
            activeTempFile = null
            try { pfd?.close() } catch (s: Throwable) { initError.addSuppressed(s) }
            try { metadataExecutor.shutdownNow() } catch (_: Throwable) {}
            throw initError
        }
    }

    private fun copyDescriptorViaPread(fd: FileDescriptor, destFile: File) {
        val directBuf = ByteBuffer.allocateDirect(64 * 1024)
        val byteArr = ByteArray(64 * 1024)
        var offset = 0L
        FileOutputStream(destFile).use { fos ->
            while (true) {
                directBuf.clear()
                val read = try {
                    Os.pread(fd, directBuf, offset)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Error reading source descriptor at offset $offset: ${e.message}", e)
                }
                if (read <= 0) break
                directBuf.flip()
                directBuf.get(byteArr, 0, read)
                fos.write(byteArr, 0, read)
                offset += read
            }
            fos.flush()
        }
        if (offset == 0L) {
            throw IllegalArgumentException("SQLite source file is empty (0 bytes).")
        }
    }

    companion object {
        private const val FAST_ROWID_PAGINATION_THRESHOLD = 50_000
        fun escapeIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
    }

    override fun getLastError(): String? = synchronized(stateLock) { lastErrorMsg }

    override fun getStateVersion(): Long = synchronized(stateLock) { stateVersionCounter }

    private fun captureSnapshot(): SessionStateSnapshot = synchronized(stateLock) {
        val status = when {
            isClosed || dbConn == null -> SqliteSessionStatus.CLOSED
            lastErrorMsg != null -> SqliteSessionStatus.ERROR
            tables.isEmpty() -> SqliteSessionStatus.EMPTY_DB
            isLoadingMeta || activeTableMeta == null -> SqliteSessionStatus.LOADING_META
            else -> SqliteSessionStatus.READY
        }
        SessionStateSnapshot(
            status = status,
            tableIndex = activeTableIdx,
            token = activeTableToken,
            stateVersion = stateVersionCounter,
            meta = activeTableMeta,
            errorMsg = lastErrorMsg,
            connection = dbConn
        )
    }

    private inline fun <T> withActiveConnection(
        expectedToken: Long,
        expectedTableIdx: Int,
        block: (SqliteDbConnection) -> T
    ): T {
        val conn: SqliteDbConnection
        synchronized(stateLock) {
            if (isClosed || dbConn == null) {
                throw IllegalStateException("SQLite session is closed")
            }
            if (activeTableToken != expectedToken || activeTableIdx != expectedTableIdx) {
                throw StaleTableOperationException()
            }
            inFlightOperations++
            conn = dbConn!!
        }
        try {
            return block(conn)
        } finally {
            synchronized(stateLock) {
                inFlightOperations = (inFlightOperations - 1).coerceAtLeast(0)
                if (inFlightOperations == 0) {
                    stateLock.notifyAll()
                }
            }
        }
    }

    private class StaleTableOperationException : RuntimeException()

    private fun publishQueryErrorIfCurrent(expectedToken: Long, expectedTableIdx: Int, message: String): Boolean {
        val listenerToNotify: Runnable?
        val published = synchronized(stateLock) {
            if (!isClosed && activeTableToken == expectedToken && activeTableIdx == expectedTableIdx) {
                lastErrorMsg = message
                stateVersionCounter++
                stateLock.notifyAll()
                listenerToNotify = onMetaLoadedListener
                true
            } else {
                listenerToNotify = null
                false
            }
        }
        if (published) {
            listenerToNotify?.run()
        }
        return published
    }

    private fun openRangeCursor(
        conn: SqliteDbConnection,
        meta: TableMeta,
        offsetRow: Long,
        limitCount: Int
    ): SqliteCursor {
        val escaped = escapeIdentifier(meta.name)
        if (!meta.isView && offsetRow >= FAST_ROWID_PAGINATION_THRESHOLD) {
            try {
                return conn.query("SELECT * FROM $escaped WHERE _rowid_ >= ${offsetRow + 1L} LIMIT $limitCount;")
            } catch (_: Exception) {
            }
        }
        return conn.query("SELECT * FROM $escaped LIMIT $limitCount OFFSET $offsetRow;")
    }

    private fun scheduleTableMetaLoad(targetIndex: Int) {
        val tokenToLoad: Long
        val listenerToNotify: Runnable?
        synchronized(stateLock) {
            if (isClosed || targetIndex !in tables.indices) return
            activeTableIdx = targetIndex
            activeTableMeta = null
            lastErrorMsg = null
            isLoadingMeta = true
            activeTableToken++
            stateVersionCounter++
            tokenToLoad = activeTableToken
            listenerToNotify = onMetaLoadedListener
            stateLock.notifyAll()
        }
        listenerToNotify?.run()
        try {
            metadataExecutor.execute {
                loadActiveTableMetaInBackground(targetIndex, tokenToLoad)
            }
        } catch (_: Throwable) {}
    }

    private fun loadActiveTableMetaInBackground(targetIndex: Int, token: Long) {
        val tableName = synchronized(stateLock) {
            if (isClosed || activeTableToken != token || activeTableIdx != targetIndex) {
                return
            }
            tables.getOrNull(targetIndex)
        } ?: return

        var isView = false
        var count = 0L
        val cols = mutableListOf<String>()
        var errorOccurred = false
        var errorText: String? = null

        try {
            withActiveConnection(token, targetIndex) { conn ->
                conn.query(
                    "SELECT type FROM sqlite_master WHERE name = ? AND type IN ('table', 'view');",
                    arrayOf(tableName)
                ).use { c ->
                    if (c.moveToFirst()) {
                        isView = (c.getString(0) == "view")
                    } else {
                        throw IllegalStateException("Object '$tableName' not found in sqlite_master")
                    }
                }

                try {
                    conn.query("PRAGMA table_info(${escapeIdentifier(tableName)});").use { c ->
                        val nameIdx = c.getColumnIndex("name")
                        while (c.moveToNext()) {
                            if (nameIdx >= 0) {
                                c.getString(nameIdx)?.let { cols.add(it) }
                            }
                        }
                    }
                } catch (_: Exception) {}

                if (cols.isEmpty()) {
                    conn.query("SELECT * FROM ${escapeIdentifier(tableName)} LIMIT 1;").use { c ->
                        cols.addAll(c.columnNames)
                    }
                }

                if (cols.isEmpty()) {
                    throw IllegalStateException("Не удалось прочитать схему таблицы \"$tableName\"")
                }

                conn.query("SELECT COUNT(*) FROM ${escapeIdentifier(tableName)};").use { c ->
                    if (c.moveToFirst()) {
                        count = c.getLong(0)
                    } else {
                        throw IllegalStateException("Не удалось получить количество строк таблицы \"$tableName\"")
                    }
                }
            }
        } catch (_: StaleTableOperationException) {
            return
        } catch (e: Exception) {
            errorOccurred = true
            errorText = "Не удалось прочитать схему таблицы \"$tableName\": ${e.message}"
        }

        val meta = if (!errorOccurred) TableMeta(tableName, isView, cols, count) else null

        val listenerToNotify: Runnable?
        val shouldNotify = synchronized(stateLock) {
            if (!isClosed && activeTableToken == token && activeTableIdx == targetIndex) {
                if (errorOccurred) {
                    lastErrorMsg = errorText
                    activeTableMeta = null
                } else {
                    lastErrorMsg = null
                    activeTableMeta = meta
                }
                isLoadingMeta = false
                stateVersionCounter++
                stateLock.notifyAll()
                listenerToNotify = onMetaLoadedListener
                true
            } else {
                listenerToNotify = null
                false
            }
        }

        if (shouldNotify) {
            listenerToNotify?.run()
        }
    }

    fun waitForMetaForTesting(timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(stateLock) {
            while (!isClosed && isLoadingMeta) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                try {
                    stateLock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    override fun getSheetNames(): List<String> = synchronized(stateLock) {
        if (tables.isEmpty()) listOf("Empty DB") else tables.toList()
    }

    override fun getActiveSheetIndex(): Int = synchronized(stateLock) { activeTableIdx }

    override fun setActiveSheetIndex(index: Int) {
        val shouldSchedule = synchronized(stateLock) {
            !isClosed && index in tables.indices && index != activeTableIdx
        }
        if (shouldSchedule) {
            scheduleTableMetaLoad(index)
        }
    }

    private fun formatCursorValue(cursor: SqliteCursor, columnIndex: Int): String {
        return when (val type = cursor.getType(columnIndex)) {
            SqliteFieldType.NULL -> "NULL"
            SqliteFieldType.INTEGER -> cursor.getLong(columnIndex).toString()
            SqliteFieldType.FLOAT -> cursor.getDouble(columnIndex).toString()
            SqliteFieldType.STRING -> cursor.getString(columnIndex)
                ?: throw IllegalStateException("Inconsistent NULL string value at column $columnIndex")
            SqliteFieldType.BLOB -> "[BLOB]"
            else -> throw IllegalStateException("Unknown SQLite field type: $type")
        }
    }

    override fun getPageText(pageIndex: Int): String {
        val cleanIdx = pageIndex and LIGHT_THEME_PAGE_FLAG.inv()
        val conn: SqliteDbConnection
        val tableName: String
        synchronized(stateLock) {
            if (isClosed || dbConn == null || tables.isEmpty()) return ""
            val targetIdx = if (cleanIdx in tables.indices) cleanIdx else activeTableIdx
            tableName = tables.getOrNull(targetIdx) ?: return ""
            inFlightOperations++
            conn = dbConn!!
        }
        return try {
            val sb = StringBuilder()
            conn.query("SELECT * FROM ${escapeIdentifier(tableName)} LIMIT 2000;").use { c ->
                val colCount = c.columnCount
                var firstRow = true
                while (c.moveToNext()) {
                    if (synchronized(stateLock) { isClosed }) return ""
                    if (!firstRow) sb.append('\n')
                    firstRow = false
                    for (col in 0 until colCount) {
                        if (col > 0) sb.append('\t')
                        val cellStr = try {
                            formatCursorValue(c, col)
                        } catch (_: Exception) {
                            ""
                        }
                        sb.append(cellStr.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '))
                    }
                }
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        } finally {
            synchronized(stateLock) {
                inFlightOperations = (inFlightOperations - 1).coerceAtLeast(0)
                if (inFlightOperations == 0) {
                    stateLock.notifyAll()
                }
            }
        }
    }

    override fun search(query: String, startPage: Int): Long {
        if (query.isBlank()) return -1L
        val tableCount = synchronized(stateLock) { tables.size }
        for (i in startPage.coerceAtLeast(0) until tableCount) {
            if (getPageText(i).contains(query, ignoreCase = true)) {
                return i.toLong()
            }
        }
        return -1L
    }

    override fun getCellText(row: Int, col: Int): String {
        val snap = captureSnapshot()
        when (snap.status) {
            SqliteSessionStatus.CLOSED -> throw IllegalStateException("Database connection is closed")
            SqliteSessionStatus.ERROR -> throw IllegalArgumentException(snap.errorMsg ?: "Table read error")
            SqliteSessionStatus.LOADING_META -> throw IllegalStateException("Table metadata is still loading")
            SqliteSessionStatus.EMPTY_DB -> return ""
            SqliteSessionStatus.READY -> {}
        }
        val meta = snap.meta ?: throw IllegalStateException("Table metadata is unavailable")
        if (row < 0 || row >= meta.rowCount || col < 0 || col >= meta.columns.size) return ""

        val tableName = meta.name
        return try {
            withActiveConnection(snap.token, snap.tableIndex) { conn ->
                openRangeCursor(conn, meta, row.toLong(), 1).use { c ->
                    if (synchronized(stateLock) { activeTableToken != snap.token || activeTableIdx != snap.tableIndex || isClosed }) {
                        return ""
                    }
                    if (c.moveToFirst() && col < c.columnCount) {
                        val formatted = formatCursorValue(c, col)
                        if (synchronized(stateLock) { activeTableToken != snap.token || activeTableIdx != snap.tableIndex || isClosed }) {
                            ""
                        } else {
                            formatted
                        }
                    } else {
                        throw IllegalStateException("Row $row is missing from cursor of table \"$tableName\"")
                    }
                }
            }
        } catch (_: StaleTableOperationException) {
            ""
        } catch (e: Exception) {
            val errorMsg = "Не удалось прочитать ячейку [$row, $col] таблицы \"$tableName\": ${e.message}"
            val isCurrent = publishQueryErrorIfCurrent(snap.token, snap.tableIndex, errorMsg)
            if (!isCurrent) return ""
            throw IllegalArgumentException(errorMsg, e)
        }
    }

    override fun getSelectedRangeText(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        val snap = captureSnapshot()
        when (snap.status) {
            SqliteSessionStatus.CLOSED -> throw IllegalStateException("Database connection is closed")
            SqliteSessionStatus.ERROR -> throw IllegalArgumentException(snap.errorMsg ?: "Table read error")
            SqliteSessionStatus.LOADING_META -> throw IllegalStateException("Table metadata is still loading")
            SqliteSessionStatus.EMPTY_DB -> return ""
            SqliteSessionStatus.READY -> {}
        }
        val meta = snap.meta ?: return ""

        val requestedStart = minOf(startRow, endRow).toLong().coerceAtLeast(0L)
        val requestedEnd = maxOf(startRow, endRow).toLong()
        val lastExistingRow = (meta.rowCount - 1L).coerceAtLeast(-1L)
        val endR = minOf(requestedEnd, lastExistingRow)
        val minC = minOf(startCol, endCol).coerceAtLeast(0)
        val maxC = maxOf(startCol, endCol).coerceAtMost(meta.columns.size - 1)

        if (requestedStart > endR || minC > maxC) return ""

        val limitR = (endR - requestedStart + 1L).coerceAtMost(10_000L).toInt()
        val tableName = meta.name
        val rowsBuffer = mutableListOf<String>()

        return try {
            withActiveConnection(snap.token, snap.tableIndex) { conn ->
                openRangeCursor(conn, meta, requestedStart, limitR).use { c ->
                    while (c.moveToNext()) {
                        if (synchronized(stateLock) { activeTableToken != snap.token || activeTableIdx != snap.tableIndex || isClosed }) {
                            return ""
                        }
                        val lineSb = StringBuilder()
                        for (colIdx in minC..maxC) {
                            if (colIdx < c.columnCount) {
                                lineSb.append(formatCursorValue(c, colIdx))
                            }
                            if (colIdx < maxC) lineSb.append('\t')
                        }
                        rowsBuffer.add(lineSb.toString())
                    }
                }
            }
            if (synchronized(stateLock) { activeTableToken != snap.token || activeTableIdx != snap.tableIndex || isClosed }) {
                ""
            } else {
                rowsBuffer.joinToString("\n")
            }
        } catch (_: StaleTableOperationException) {
            ""
        } catch (e: Exception) {
            val errorMsg = "Не удалось прочитать диапазон таблицы \"$tableName\": ${e.message}"
            val isCurrent = publishQueryErrorIfCurrent(snap.token, snap.tableIndex, errorMsg)
            if (!isCurrent) return ""
            throw IllegalArgumentException(errorMsg, e)
        }
    }

    override val pageCount: Int get() = 1

    override fun fetchVisibleRows(offsetIndex: Long, limit: Int): List<List<String>> {
        if (limit <= 0) return emptyList()
        val safeOffset = offsetIndex.coerceAtLeast(0L)
        val safeLimit = limit.coerceIn(1, 1000)

        val snap = captureSnapshot()
        when (snap.status) {
            SqliteSessionStatus.CLOSED -> throw IllegalStateException("Database connection is closed")
            SqliteSessionStatus.ERROR -> throw IllegalArgumentException(snap.errorMsg ?: "Table read error")
            SqliteSessionStatus.LOADING_META -> throw IllegalStateException("Table metadata is still loading")
            SqliteSessionStatus.EMPTY_DB -> return emptyList()
            SqliteSessionStatus.READY -> {}
        }
        val meta = snap.meta ?: return emptyList()
        if (meta.rowCount <= 0L || safeOffset >= meta.rowCount) return emptyList()

        val tableName = meta.name
        val result = mutableListOf<List<String>>()
        return try {
            withActiveConnection(snap.token, snap.tableIndex) { conn ->
                openRangeCursor(conn, meta, safeOffset, safeLimit).use { c ->
                    while (c.moveToNext()) {
                        if (synchronized(stateLock) { activeTableToken != snap.token || activeTableIdx != snap.tableIndex || isClosed }) {
                            return emptyList()
                        }
                        val row = ArrayList<String>(c.columnCount)
                        for (i in 0 until c.columnCount) {
                            row.add(formatCursorValue(c, i))
                        }
                        result.add(row)
                    }
                }
            }
            if (synchronized(stateLock) { activeTableToken != snap.token || activeTableIdx != snap.tableIndex || isClosed }) {
                emptyList()
            } else {
                result
            }
        } catch (_: StaleTableOperationException) {
            emptyList()
        } catch (e: Exception) {
            val errorMsg = "Не удалось прочитать строки таблицы \"$tableName\": ${e.message}"
            val isCurrent = publishQueryErrorIfCurrent(snap.token, snap.tableIndex, errorMsg)
            if (!isCurrent) return emptyList()
            throw IllegalArgumentException(errorMsg, e)
        }
    }

    override fun getDocumentWidth(): Float {
        val meta = synchronized(stateLock) { activeTableMeta } ?: return 0f
        if (meta.columns.isEmpty()) return 0f
        return 84f + meta.columns.size * 170f
    }

    override fun getDocumentHeight(): Float {
        val meta = synchronized(stateLock) { activeTableMeta } ?: return 0f
        val rowCount = meta.rowCount
        if (rowCount <= 0L) return 0f
        val renderableRows = rowCount.coerceAtMost(Int.MAX_VALUE.toLong())
        return 56f + renderableRows * 48f
    }

    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        val snap = captureSnapshot()
        if (snap.status == SqliteSessionStatus.CLOSED) return
        val isLightTheme = (pageIndex and LIGHT_THEME_PAGE_FLAG) != 0

        val zoom = (zoomPermille / 1000f).coerceIn(0.5f, 4f)
        val headerHeight = 56f * zoom
        val headerWidth = 84f * zoom
        val cellHeight = 48f * zoom

        val paintCellBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_CELL_BG_COLOR else DARK_SHEET_CELL_BG_COLOR
            style = Paint.Style.FILL
        }
        val paintText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_TEXT_COLOR else DARK_SHEET_TEXT_COLOR
            textSize = 26f * zoom
            typeface = android.graphics.Typeface.SANS_SERIF
        }
        val paintHeader = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_HEADER_TEXT_COLOR else DARK_SHEET_HEADER_TEXT_COLOR
            textSize = 26f * zoom
            typeface = android.graphics.Typeface.SANS_SERIF
            isFakeBoldText = true
        }
        val paintHeaderBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_HEADER_BG_COLOR else DARK_SHEET_HEADER_BG_COLOR
            style = Paint.Style.FILL
        }
        val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) LIGHT_SHEET_GRID_COLOR else DARK_SHEET_GRID_COLOR
            strokeWidth = 2f
        }

        if (snap.status != SqliteSessionStatus.READY || snap.meta == null) {
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (isLightTheme) LIGHT_SHEET_BG_COLOR else DARK_SHEET_BG_COLOR)
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), headerHeight, paintHeaderBg)
            canvas.drawLine(0f, headerHeight, bitmap.width.toFloat(), headerHeight, paintGrid)
            val statusLabel = when (snap.status) {
                SqliteSessionStatus.LOADING_META -> "SQLite..."
                SqliteSessionStatus.EMPTY_DB -> "0 tables"
                SqliteSessionStatus.ERROR -> "SQLite Error"
                else -> ""
            }
            if (statusLabel.isNotEmpty()) {
                canvas.drawText(statusLabel, 24f * zoom, 36f * zoom, paintHeader)
            }
            return
        }

        val metaSnap = snap.meta
        val cols = metaSnap.columns
        val totalRows = metaSnap.rowCount
        if (cols.isEmpty()) {
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (isLightTheme) LIGHT_SHEET_BG_COLOR else DARK_SHEET_BG_COLOR)
            return
        }

        val dataColCount = cols.size
        val unzoomedAvailGridW = (bitmap.width.toFloat() - 84f).coerceAtLeast(170f)
        val baseUnzoomedCellW = max(170f, unzoomedAvailGridW / dataColCount)
        val cellWidth = baseUnzoomedCellW * zoom

        val renderableTotalRows = totalRows.coerceIn(0L, Int.MAX_VALUE.toLong())
        val totalTableW = headerWidth + dataColCount * cellWidth
        val totalTableH = headerHeight + renderableTotalRows * cellHeight
        val maxEffX = (totalTableW - bitmap.width.toFloat()).coerceAtLeast(0f)
        val maxEffY = (totalTableH - bitmap.height.toFloat()).coerceAtLeast(0f)

        val effectiveTileX = tileX.toFloat().coerceIn(0f, maxEffX)
        val effectiveTileY = tileY.toFloat().coerceIn(0f, maxEffY)

        val maxColIndex = cols.size - 1
        val startCol = (effectiveTileX / cellWidth).toInt().coerceAtLeast(0)
        val endCol = (startCol + (bitmap.width / cellWidth).toInt() + 2).coerceAtMost(maxColIndex)

        val maxRowIndex = (totalRows - 1L).coerceAtLeast(-1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val startRow = (effectiveTileY / cellHeight).toInt().coerceAtLeast(0)
        val endRow = if (maxRowIndex >= 0) {
            (startRow + (bitmap.height / cellHeight).toInt() + 2).coerceAtMost(maxRowIndex)
        } else {
            -1
        }
        val subTileY = effectiveTileY % cellHeight

        val localRows = ArrayList<List<String>>()
        if (totalRows > 0L && startRow <= endRow && startCol <= endCol) {
            val fetchCount = (endRow - startRow + 1).coerceIn(1, 500)
            try {
                withActiveConnection(snap.token, snap.tableIndex) { conn ->
                    openRangeCursor(conn, metaSnap, startRow.toLong(), fetchCount).use { c ->
                        while (c.moveToNext()) {
                            if (synchronized(stateLock) { activeTableToken != snap.token || activeTableIdx != snap.tableIndex || isClosed }) {
                                return
                            }
                            val rowVals = ArrayList<String>(endCol - startCol + 1)
                            for (colIdx in startCol..endCol) {
                                if (colIdx in 0 until c.columnCount) {
                                    rowVals.add(formatCursorValue(c, colIdx))
                                } else {
                                    rowVals.add("")
                                }
                            }
                            localRows.add(rowVals)
                        }
                    }
                }
            } catch (_: StaleTableOperationException) {
                return
            } catch (e: Exception) {
                val errorMsg = "Не удалось прочитать строки таблицы \"${metaSnap.name}\": ${e.message}"
                publishQueryErrorIfCurrent(snap.token, snap.tableIndex, errorMsg)
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (isLightTheme) LIGHT_SHEET_BG_COLOR else DARK_SHEET_BG_COLOR)
                canvas.drawRect(0f, 0f, bitmap.width.toFloat(), headerHeight, paintHeaderBg)
                return
            }
        }

        if (synchronized(stateLock) { activeTableToken != snap.token || activeTableIdx != snap.tableIndex || isClosed }) {
            return
        }

        val canvas = Canvas(bitmap)
        canvas.drawColor(if (isLightTheme) LIGHT_SHEET_BG_COLOR else DARK_SHEET_BG_COLOR)

        if (startCol > endCol) return

        val tableRightX = (headerWidth + (endCol + 1) * cellWidth - effectiveTileX).coerceIn(headerWidth, bitmap.width.toFloat())
        val tableBottomY = if (localRows.isNotEmpty()) {
            (headerHeight + localRows.size * cellHeight - subTileY).coerceIn(headerHeight, bitmap.height.toFloat())
        } else {
            headerHeight
        }

        if (tableBottomY > headerHeight) {
            canvas.drawRect(headerWidth, headerHeight, tableRightX, tableBottomY, paintCellBg)
        }

        for (i in localRows.indices) {
            val rowY = headerHeight + i * cellHeight - subTileY
            if (rowY + cellHeight >= headerHeight && rowY <= bitmap.height) {
                val rowValues = localRows[i]
                for (colOffset in rowValues.indices) {
                    val colIdx = startCol + colOffset
                    val colX = headerWidth + colIdx * cellWidth - effectiveTileX
                    if (colX + cellWidth >= headerWidth && colX <= bitmap.width) {
                        val text = rowValues[colOffset]
                        if (text.isNotEmpty()) {
                            val clippedText = TextUtils.ellipsize(
                                text,
                                paintText,
                                (cellWidth - 16f * zoom).coerceAtLeast(10f),
                                TextUtils.TruncateAt.END
                            ).toString()
                            canvas.save()
                            canvas.clipRect(
                                maxOf(headerWidth, colX + 2f),
                                maxOf(headerHeight, rowY + 2f),
                                colX + cellWidth - 2f,
                                rowY + cellHeight - 2f
                            )
                            canvas.drawText(clippedText, colX + 10f * zoom, rowY + 33f * zoom, paintText)
                            canvas.restore()
                        }
                        canvas.drawLine(colX + cellWidth, maxOf(headerHeight, rowY), colX + cellWidth, rowY + cellHeight, paintGrid)
                    }
                }
                canvas.drawLine(headerWidth, rowY + cellHeight, tableRightX, rowY + cellHeight, paintGrid)
            }
        }

        canvas.drawRect(0f, 0f, tableRightX, headerHeight, paintHeaderBg)
        for (c in startCol..endCol) {
            val colX = headerWidth + c * cellWidth - effectiveTileX
            if (colX + cellWidth >= headerWidth && colX <= bitmap.width && c in cols.indices) {
                canvas.save()
                canvas.clipRect(maxOf(headerWidth, colX), 0f, colX + cellWidth, headerHeight)
                val colName = cols[c]
                val clippedColName = TextUtils.ellipsize(
                    colName,
                    paintHeader,
                    (cellWidth - 16f * zoom).coerceAtLeast(10f),
                    TextUtils.TruncateAt.END
                ).toString()
                canvas.drawText(clippedColName, colX + 10f * zoom, 36f * zoom, paintHeader)
                canvas.drawLine(colX + cellWidth, 0f, colX + cellWidth, headerHeight, paintGrid)
                canvas.restore()
            }
        }
        canvas.drawLine(0f, headerHeight, tableRightX, headerHeight, paintGrid)

        for (i in localRows.indices) {
            val r = startRow + i
            val rowY = headerHeight + i * cellHeight - subTileY
            if (rowY + cellHeight >= headerHeight && rowY <= bitmap.height) {
                val clampedTop = maxOf(headerHeight, rowY)
                canvas.drawRect(0f, clampedTop, headerWidth, rowY + cellHeight, paintHeaderBg)
                canvas.drawText("${r + 1}", 14f * zoom, rowY + 33f * zoom, paintHeader)
                canvas.drawLine(0f, rowY + cellHeight, headerWidth, rowY + cellHeight, paintGrid)
                canvas.drawLine(headerWidth, clampedTop, headerWidth, rowY + cellHeight, paintGrid)
            }
        }

        canvas.drawRect(0f, 0f, headerWidth, headerHeight, paintHeaderBg)
        canvas.drawLine(headerWidth, 0f, headerWidth, tableBottomY, paintGrid)
        canvas.drawLine(0f, headerHeight, headerWidth, headerHeight, paintGrid)
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(key.zoom, key.x, key.y, 1000, bitmap)
    }

    override fun close() {
        val connToClose: SqliteDbConnection?
        val tempToDelete: File?
        synchronized(stateLock) {
            if (isClosed) return
            isClosed = true
            onMetaLoadedListener = null
            activeTableToken++
            stateVersionCounter++
            stateLock.notifyAll()

            val deadline = System.currentTimeMillis() + 3000L
            while (inFlightOperations > 0) {
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0L) break
                try {
                    stateLock.wait(rem)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            connToClose = dbConn
            dbConn = null
            tempToDelete = activeTempFile
            activeTempFile = null
        }

        try {
            metadataExecutor.shutdownNow()
        } catch (_: Throwable) {}

        try {
            connToClose?.close()
        } catch (_: Throwable) {
        } finally {
            try { pfd?.close() } catch (_: Throwable) {}
            com.omnireader.core.engine.ScratchFiles.delete(tempToDelete)
        }
    }
}