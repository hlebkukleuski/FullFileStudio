/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.feature.office

import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.util.Locale
import me.hlebkukleuski.fullfilestudio.core.document.SpreadsheetGridSession
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

class Rfc4180CsvTsvSession(
    private val pfd: ParcelFileDescriptor,
    private val ext: String
) : SpreadsheetGridSession() {
    init {
        parseStreamingRfc4180()
    }

    private fun parseStreamingRfc4180() {
        try {
            val source = ZeroCacheReadableSource(pfd)
            val fileSize = source.size.let { if (it > 0L) it else -1L }
            val sampleLen = minOf(if (fileSize > 0L) fileSize else 16384L, 16384L).toInt()
            val sampleBytes = if (sampleLen > 0) {
                val buf = ByteBuffer.allocateDirect(sampleLen)
                val r = try { source.readAt(0L, buf) } catch (_: Throwable) { 0 }
                if (r > 0) {
                    val arr = ByteArray(r)
                    buf.flip()
                    buf.get(arr)
                    arr
                } else ByteArray(0)
            } else ByteArray(0)
            val det = CsvParser.detectFromSample(sampleBytes, sampleBytes.size, ext)
            val stream = OfficePreadInputStream(source, fileSize)
            repeat(det.bomSkip) { stream.read() }
            sheets.add(CsvParser.parseStream(stream, det, ext))
        } catch (e: Exception) {
            lastErrorMsg = "Ошибка чтения таблицы ${ext.uppercase(Locale.ROOT)}: ${e.localizedMessage}"
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { pfd.close() } catch (_: Exception) {}
    }
}
