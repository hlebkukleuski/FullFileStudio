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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal object CleanRoomIsoRockRidge {
    data class SuspMeta(
        val name: String? = null,
        val symlinkTarget: String? = null,
        val posixMode: Int? = null,
        val posixUid: Int? = null,
        val posixGid: Int? = null,
        val childLinkLba: Long? = null,
        val parentLinkLba: Long? = null,
        val relocated: Boolean = false,
        val spFound: Boolean = false,
        val spSkip: Int = 0,
        val hasRockRidge: Boolean = false
    )

    fun parseSystemUseArea(
        recordBytes: ByteArray,
        recPos: Int,
        recLen: Int,
        nameLen: Int,
        skipBytes: Int,
        readBlock: (lba: Long, offset: Int, len: Int) -> ByteArray?
    ): SuspMeta {
        val suStart = recPos + 33 + nameLen + (if ((nameLen and 1) == 0) 1 else 0) + skipBytes
        val suEnd = recPos + recLen
        if (suStart >= suEnd || suEnd > recordBytes.size) return SuspMeta()

        val nameSb = StringBuilder()
        var hasNm = false
        val linkSb = StringBuilder()
        var hasSl = false
        var slNeedsSep = false
        var slPrevContinued = false
        var posixMode: Int? = null
        var posixUid: Int? = null
        var posixGid: Int? = null
        var childLba: Long? = null
        var parentLba: Long? = null
        var relocated = false
        var spFound = false
        var spSkip = 0
        var hasRr = false

        var curBuf = recordBytes
        var curPos = suStart
        var curEnd = suEnd
        var ceHops = 0

        while (ceHops < 32) {
            var nextCeLba = -1L
            var nextCeOff = 0
            var nextCeLen = 0
            var p = curPos
            while (p + 4 <= curEnd) {
                val s0 = curBuf[p].toInt() and 0xFF
                val s1 = curBuf[p + 1].toInt() and 0xFF
                val len = curBuf[p + 2].toInt() and 0xFF
                if (len < 4 || p + len > curEnd) break
                val sig = (s0 shl 8) or s1
                when (sig) {
                    0x5350 -> {
                        if (len >= 7 && (curBuf[p + 4].toInt() and 0xFF) == 0xBE && (curBuf[p + 5].toInt() and 0xFF) == 0xEF) {
                            spFound = true
                            spSkip = curBuf[p + 6].toInt() and 0xFF
                        }
                    }
                    0x5354 -> {
                        nextCeLba = -1L
                        break
                    }
                    0x4345 -> {
                        if (len >= 28) {
                            nextCeLba = u32le(curBuf, p + 4)
                            nextCeOff = u32le(curBuf, p + 12).toInt()
                            nextCeLen = u32le(curBuf, p + 20).toInt()
                        }
                    }
                    0x4552 -> {
                        if (len >= 8) {
                            val idLen = curBuf[p + 4].toInt() and 0xFF
                            if (p + 8 + idLen <= p + len) {
                                val extId = String(curBuf, p + 8, idLen, StandardCharsets.US_ASCII)
                                if (extId.contains("RRIP") || extId.contains("1282")) hasRr = true
                            }
                        }
                    }
                    0x5252 -> hasRr = true
                    0x4E4D -> {
                        if (len >= 5) {
                            hasRr = true
                            val nmFlags = curBuf[p + 4].toInt() and 0xFF
                            when {
                                (nmFlags and 0x02) != 0 -> { nameSb.setLength(0); nameSb.append("."); hasNm = true }
                                (nmFlags and 0x04) != 0 -> { nameSb.setLength(0); nameSb.append(".."); hasNm = true }
                                else -> {
                                    if (len > 5) {
                                        nameSb.append(String(curBuf, p + 5, len - 5, StandardCharsets.UTF_8))
                                        hasNm = true
                                    }
                                }
                            }
                        }
                    }
                    0x5058 -> {
                        if (len >= 36) {
                            hasRr = true
                            posixMode = u32le(curBuf, p + 4).toInt()
                            posixUid = u32le(curBuf, p + 20).toInt()
                            posixGid = u32le(curBuf, p + 28).toInt()
                        }
                    }
                    0x534C -> {
                        if (len >= 5) {
                            hasRr = true
                            hasSl = true
                            var cp = p + 5
                            val cEnd = p + len
                            while (cp + 2 <= cEnd) {
                                val cFlags = curBuf[cp].toInt() and 0xFF
                                val cLen = curBuf[cp + 1].toInt() and 0xFF
                                if (cp + 2 + cLen > cEnd) break
                                if ((cFlags and 0x08) != 0) {
                                    linkSb.append('/')
                                    slNeedsSep = false
                                } else {
                                    if (slNeedsSep && !slPrevContinued) linkSb.append('/')
                                    when {
                                        (cFlags and 0x02) != 0 -> linkSb.append('.')
                                        (cFlags and 0x04) != 0 -> linkSb.append("..")
                                        else -> if (cLen > 0) linkSb.append(String(curBuf, cp + 2, cLen, StandardCharsets.UTF_8))
                                    }
                                    slNeedsSep = true
                                }
                                slPrevContinued = (cFlags and 0x01) != 0
                                cp += 2 + cLen
                            }
                        }
                    }
                    0x434C -> {
                        if (len >= 12) {
                            hasRr = true
                            childLba = u32le(curBuf, p + 4)
                        }
                    }
                    0x504C -> {
                        if (len >= 12) {
                            hasRr = true
                            parentLba = u32le(curBuf, p + 4)
                        }
                    }
                    0x5245 -> {
                        hasRr = true
                        relocated = true
                    }
                }
                p += len
            }
            if (nextCeLba >= 0L && nextCeLen in 4..65536 && nextCeOff >= 0) {
                val ceData = readBlock(nextCeLba, nextCeOff, nextCeLen) ?: break
                curBuf = ceData
                curPos = 0
                curEnd = ceData.size
                ceHops++
            } else {
                break
            }
        }

        return SuspMeta(
            name = if (hasNm && nameSb.isNotEmpty()) nameSb.toString() else null,
            symlinkTarget = if (hasSl && linkSb.isNotEmpty()) linkSb.toString() else null,
            posixMode = posixMode,
            posixUid = posixUid,
            posixGid = posixGid,
            childLinkLba = childLba,
            parentLinkLba = parentLba,
            relocated = relocated,
            spFound = spFound,
            spSkip = spSkip,
            hasRockRidge = hasRr
        )
    }

    private fun u32le(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 4 > b.size) return 0L
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    fun formatPosixMode(mode: Int): String {
        val ft = when ((mode ushr 12) and 0xF) {
            0x4 -> 'd'
            0xA -> 'l'
            else -> '-'
        }
        val bits = "rwxrwxrwx"
        val sb = StringBuilder(10).append(ft)
        for (i in 0 until 9) {
            sb.append(if ((mode and (1 shl (8 - i))) != 0) bits[i] else '-')
        }
        return sb.toString()
    }

    fun probePvdRockRidge(
        pvdRootRecord: ByteArray,
        readRange: (byteOffset: Long, len: Int) -> ByteArray?
    ): Pair<Boolean, Int> {
        if (pvdRootRecord.size < 34) return false to 0
        val bb = ByteBuffer.wrap(pvdRootRecord).order(ByteOrder.LITTLE_ENDIAN)
        val rootLba = bb.getInt(2).toLong() and 0xFFFFFFFFL
        val sector = readRange(rootLba * 2048L, 2048) ?: return false to 0
        if (sector.size < 34) return false to 0
        val dotLen = sector[0].toInt() and 0xFF
        val dotNameLen = sector[32].toInt() and 0xFF
        if (dotLen < 34 || dotLen > sector.size) return false to 0
        val meta = parseSystemUseArea(sector, 0, dotLen, dotNameLen, 0) { lba, off, len ->
            readRange(lba * 2048L + off, len)
        }
        val active = meta.spFound || meta.hasRockRidge
        return active to meta.spSkip
    }
}
