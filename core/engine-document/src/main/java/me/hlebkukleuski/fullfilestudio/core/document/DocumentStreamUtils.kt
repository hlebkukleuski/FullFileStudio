/*
 * Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.document

import android.graphics.Color
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

internal const val LIGHT_THEME_PAGE_FLAG = 1 shl 30
internal const val ILLUSTRATION_ONLY_PAGE_FLAG = 1 shl 29
internal const val LANG_ORDINAL_SHIFT = 24
internal const val PAGE_INDEX_MASK = 0x000FFFFF
internal val DARK_PRESENTATION_BG_COLOR = Color.rgb(0x12, 0x12, 0x12)
internal val LIGHT_PRESENTATION_BG_COLOR = Color.rgb(0xF5, 0xF7, 0xFA)

fun resolveStreamDescriptorSize(fd: FileDescriptor): Long {
    val statSize = try {
        Os.fstat(fd).st_size
    } catch (_: Throwable) {
        -1L
    }
    if (statSize > 0L) return statSize

    return try {
        val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
        val end = Os.lseek(fd, 0L, OsConstants.SEEK_END)
        Os.lseek(fd, cur, OsConstants.SEEK_SET)
        if (end > 0L) end else -1L
    } catch (_: Throwable) {
        -1L
    }
}

internal class PositionalPreadInputStream(
    private val fd: FileDescriptor,
    private val totalSize: Long
) : InputStream() {
    private var position = 0L
    private val singleByte = ByteArray(1)
    private val directChunk = ByteBuffer.allocateDirect(32 * 1024)

    override fun read(): Int {
        val r = read(singleByte, 0, 1)
        return if (r == 1) (singleByte[0].toInt() and 0xFF) else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (totalSize > 0L && position >= totalSize) return -1
        val remaining = if (totalSize > 0L) (totalSize - position) else len.toLong()
        val toRead = minOf(len.toLong(), remaining, directChunk.capacity().toLong()).toInt()
        if (toRead <= 0) return -1

        directChunk.clear()
        directChunk.limit(toRead)
        val r = try {
            Os.pread(fd, directChunk, position)
        } catch (e: Exception) {
            throw IOException("pread failed at offset $position: ${e.message}", e)
        }
        if (r <= 0) return -1
        directChunk.flip()
        directChunk.get(b, off, r)
        position += r
        return r
    }
}
