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
 * DWG R13-R2018 container, LZ77 (R2004/R2007), and Reed-Solomon decoding
 * informed by GNU LibreDWG (https://www.gnu.org/software/libredwg/, Copyright (C) 2009-2026
 * Free Software Foundation, Inc., GPL-3.0-or-later) and ACadSharp
 * (https://github.com/DomCR/ACadSharp, Copyright (c) 2020-2026 DomCR, MIT License).
 * Adapted and modified for FullFileStudio in 2026.
 */

package me.hlebkukleuski.fullfilestudio.feature.gpl.dwg

import java.nio.charset.StandardCharsets
import me.hlebkukleuski.fullfilestudio.core.document.OfficeCrypto
import me.hlebkukleuski.fullfilestudio.core.engine.bridge.DwgFormatException

enum class DwgVersion(val code: String, val order: Int) {
    R13("AC1012", 13), R14("AC1014", 14), R2000("AC1015", 15), R2004("AC1018", 18),
    R2007("AC1021", 21), R2010("AC1024", 24), R2013("AC1027", 27), R2018("AC1032", 32);

    fun atLeast(v: DwgVersion): Boolean = order >= v.order
    fun below(v: DwgVersion): Boolean = order < v.order
}

class DwgSections(
    val version: DwgVersion,
    val maintenance: Int,
    val codepage: Int,
    val classes: ByteArray?,
    val classesOffset: Int,
    val handles: ByteArray,
    val handlesOffset: Int,
    val handlesEnd: Int,
    val objects: ByteArray,
    val dataStore: ByteArray? = null
)

object DwgContainer {
    private val CLASS_SENTINEL = byteArrayOf(
        0x8D.toByte(), 0xA1.toByte(), 0xC4.toByte(), 0xB8.toByte(), 0xC4.toByte(), 0xA9.toByte(), 0xF8.toByte(), 0xC5.toByte(),
        0xC0.toByte(), 0xDC.toByte(), 0xF4.toByte(), 0x5F, 0xE7.toByte(), 0xCF.toByte(), 0xB6.toByte(), 0x8A.toByte()
    )

    fun detectVersion(bytes: ByteArray): DwgVersion? {
        if (bytes.size < 6) return null
        val code = String(bytes, 0, 6, StandardCharsets.US_ASCII)
        return DwgVersion.values().firstOrNull { it.code == code }
    }

    fun open(bytes: ByteArray, password: String? = null): DwgSections {
        val version = detectVersion(bytes) ?: throw DwgFormatException("Unsupported DWG version")
        if (bytes.size < 0x80) throw DwgFormatException("DWG file is truncated")
        val maintenance = bytes[0x0B].toInt() and 0xFF
        val codepage = u16(bytes, 0x13)
        return when {
            version.below(DwgVersion.R2004) -> openR13(bytes, version, maintenance, codepage)
            version == DwgVersion.R2007 -> openR2007(bytes, version, maintenance, codepage, password)
            else -> openR2004(bytes, version, maintenance, codepage, password)
        }
    }

    internal fun u16(b: ByteArray, p: Int): Int {
        if (p < 0 || p + 2 > b.size) throw DwgFormatException("Read beyond end of data")
        return (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    }

    internal fun u32(b: ByteArray, p: Int): Long {
        if (p < 0 || p + 4 > b.size) throw DwgFormatException("Read beyond end of data")
        return (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
            ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)
    }

    internal fun i32(b: ByteArray, p: Int): Int = u32(b, p).toInt()

    internal fun i64(b: ByteArray, p: Int): Long {
        if (p < 0 || p + 8 > b.size) throw DwgFormatException("Read beyond end of data")
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
        return v
    }

    private fun openR13(bytes: ByteArray, version: DwgVersion, maintenance: Int, codepage: Int): DwgSections {
        val count = i32(bytes, 0x15)
        if (count < 3 || count > 16) throw DwgFormatException("Invalid DWG section locator count")
        var classesOffset = -1
        var classesSize = 0
        var mapOffset = -1
        var mapSize = 0
        for (i in 0 until count) {
            val p = 0x19 + i * 9
            val rec = bytes[p].toInt() and 0xFF
            val seeker = i32(bytes, p + 1)
            val size = i32(bytes, p + 5)
            when (rec) {
                1 -> { classesOffset = seeker; classesSize = size }
                2 -> { mapOffset = seeker; mapSize = size }
            }
        }
        if (mapOffset <= 0 || mapOffset >= bytes.size) throw DwgFormatException("DWG object map is missing")
        val mapEnd = if (mapSize > 0) minOf(bytes.size, mapOffset + mapSize) else bytes.size
        var classes: ByteArray? = null
        var classStart = 0
        if (classesOffset in 1 until bytes.size && classesSize > 16) {
            classes = bytes
            classStart = classesOffset
        }
        return DwgSections(version, maintenance, codepage, classes, classStart, bytes, mapOffset, mapEnd, bytes)
    }

    private fun magic(n: Int): ByteArray {
        val out = ByteArray(n)
        var seed = 1
        for (i in 0 until n) {
            seed = seed * 0x343fd + 0x269ec3
            out[i] = (seed shr 16).toByte()
        }
        return out
    }

    fun decompressR2004(src: ByteArray, srcStart: Int, srcLen: Int, dstSize: Int): ByteArray {
        val dst = ByteArray(dstSize)
        var s = srcStart
        val end = srcStart + srcLen
        var d = 0
        fun next(): Int {
            if (s >= end) throw DwgFormatException("Compressed DWG data is truncated")
            return src[s++].toInt() and 0xFF
        }
        var pendingOpcode = -1
        fun literalLength(): Int {
            val b = next()
            if (b in 0x01..0x0F) return b + 3
            if (b == 0) {
                var total = 0x0F
                var x = next()
                while (x == 0) {
                    total += 0xFF
                    x = next()
                }
                return total + x + 3
            }
            pendingOpcode = b
            return 0
        }
        fun longOffset(): Int {
            var total = 0
            var b = next()
            if (b == 0) {
                total = 0xFF
                b = next()
                while (b == 0) {
                    total += 0xFF
                    b = next()
                }
            }
            return total + b
        }
        fun copyLiteral(n: Int) {
            if (n <= 0) return
            if (d + n > dstSize || s + n > end) throw DwgFormatException("Compressed DWG literal overflows")
            System.arraycopy(src, s, dst, d, n)
            s += n
            d += n
        }
        copyLiteral(literalLength())
        while (true) {
            val opcode1 = if (pendingOpcode >= 0) pendingOpcode.also { pendingOpcode = -1 } else if (s < end) next() else break
            var compBytes: Int
            var compOffset: Int
            var litCount: Int
            when {
                opcode1 == 0x11 -> break
                opcode1 >= 0x40 -> {
                    compBytes = ((opcode1 and 0xF0) shr 4) - 1
                    val opcode2 = next()
                    compOffset = (opcode2 shl 2) or ((opcode1 and 0x0C) shr 2)
                    litCount = opcode1 and 0x03
                }
                opcode1 >= 0x21 -> {
                    compBytes = opcode1 - 0x1E
                    val first = next()
                    compOffset = (first shr 2) or (next() shl 6)
                    litCount = first and 0x03
                }
                opcode1 == 0x20 -> {
                    compBytes = longOffset() + 0x21
                    val first = next()
                    compOffset = (first shr 2) or (next() shl 6)
                    litCount = first and 0x03
                }
                opcode1 >= 0x12 -> {
                    compBytes = (opcode1 and 0x0F) + 2
                    val first = next()
                    compOffset = ((first shr 2) or (next() shl 6)) + 0x3FFF
                    litCount = first and 0x03
                }
                opcode1 == 0x10 -> {
                    compBytes = longOffset() + 9
                    val first = next()
                    compOffset = ((first shr 2) or (next() shl 6)) + 0x3FFF
                    litCount = first and 0x03
                }
                else -> throw DwgFormatException("Invalid DWG compression opcode")
            }
            val from = d - compOffset - 1
            if (from < 0 || d + compBytes > dstSize) throw DwgFormatException("Invalid DWG back reference")
            for (i in 0 until compBytes) dst[d + i] = dst[from + i]
            d += compBytes
            if (litCount == 0) litCount = literalLength()
            copyLiteral(litCount)
        }
        return dst
    }

    private class PageAddress(val address: Long, val size: Long)

    private class DwgSecurityParams(val algId: Int, val keyBits: Int, val salt: ByteArray)

    private fun parseDwgSecuritySection(sec: ByteArray): DwgSecurityParams? {
        if (sec.size < 24) return null
        val algId = i32(sec, 12)
        val keyBits = i32(sec, 16).let { if (it in 40..256) it else if (algId == 0x00006801) 40 else 128 }
        val provLen = i32(sec, 20)
        var pos = 24
        if (provLen in 0..1024 && pos + provLen <= sec.size) pos += provLen
        val salt = if (pos + 4 <= sec.size) {
            val sLen = i32(sec, pos)
            if (sLen in 1..64 && pos + 4 + sLen <= sec.size) sec.copyOfRange(pos + 4, pos + 4 + sLen) else ByteArray(0)
        } else ByteArray(0)
        return DwgSecurityParams(algId, keyBits, salt)
    }

    private fun decryptDwgPage(raw: ByteArray, params: DwgSecurityParams?, password: String): ByteArray {
        val algId = params?.algId ?: 0x00006801
        val keyBits = params?.keyBits ?: 40
        val md = java.security.MessageDigest.getInstance("SHA-1")
        if (params != null && params.salt.isNotEmpty()) md.update(params.salt)
        md.update(password.toByteArray(StandardCharsets.UTF_16LE))
        val hash = md.digest()
        val key = OfficeCrypto.cryptoApiDeriveKey(hash, keyBits, algId)
        return if (algId == 0x00006801) {
            OfficeCrypto.Rc4Cipher(key).process(raw)
        } else {
            val aligned = (raw.size / 16) * 16
            if (aligned <= 0) throw DwgFormatException("PASSWORD_REQUIRED: Incorrect password or unsupported DWG encryption.")
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(ByteArray(16)))
            cipher.doFinal(raw, 0, aligned)
        }
    }

    private fun openR2004(bytes: ByteArray, version: DwgVersion, maintenance: Int, codepage: Int, password: String?): DwgSections {
        val security = i32(bytes, 0x18)
        if ((security and 1) != 0 && password.isNullOrEmpty()) {
            throw DwgFormatException("PASSWORD_REQUIRED: DWG drawing is encrypted or password protected.")
        }
        val hdr = bytes.copyOfRange(0x80, 0x80 + 0x6C)
        val mask = magic(0x6C)
        for (i in hdr.indices) hdr[i] = (hdr[i].toInt() xor mask[i].toInt()).toByte()
        if (String(hdr, 0, 11, StandardCharsets.US_ASCII) != "AcFssFcAJMB") throw DwgFormatException("DWG file header is corrupted")
        val sectionMapId = i32(hdr, 0x5C)
        val pageMapAddress = i64(hdr, 0x54) + 0x100
        val pageMap = readSystemPage(bytes, pageMapAddress, 0x41630e3b)
        val pages = HashMap<Int, PageAddress>()
        var address = 0x100L
        var p = 0
        while (p + 8 <= pageMap.size) {
            val number = i32(pageMap, p)
            val size = u32(pageMap, p + 4)
            p += 8
            if (number < 0) {
                p += 16
            } else {
                pages[number] = PageAddress(address, size)
            }
            address += size
        }
        val sectionMapPage = pages[sectionMapId] ?: throw DwgFormatException("DWG section map is missing")
        val sectionMap = readSystemPage(bytes, sectionMapPage.address, 0x4163003b)
        val count = i32(sectionMap, 0)
        var q = 20
        val sections = HashMap<String, ByteArray>()
        var securityParams: DwgSecurityParams? = null
        class RawSecMeta(val name: String, val size: Long, val maxSize: Int, val compressed: Int, val encrypted: Int, val pageList: ArrayList<Triple<Int, Int, Long>>)
        val allMeta = ArrayList<RawSecMeta>()
        for (n in 0 until count) {
            if (q + 0x60 > sectionMap.size) break
            val size = i64(sectionMap, q)
            val pageCount = i32(sectionMap, q + 8)
            val maxSize = i32(sectionMap, q + 12)
            val compressed = i32(sectionMap, q + 20)
            val encrypted = i32(sectionMap, q + 28)
            var nameEnd = q + 32
            while (nameEnd < q + 96 && sectionMap[nameEnd].toInt() != 0) nameEnd++
            val name = String(sectionMap, q + 32, nameEnd - (q + 32), StandardCharsets.US_ASCII)
            q += 96
            val pageList = ArrayList<Triple<Int, Int, Long>>()
            for (pg in 0 until pageCount) {
                if (q + 16 > sectionMap.size) throw DwgFormatException("DWG section map is truncated")
                pageList.add(Triple(i32(sectionMap, q), i32(sectionMap, q + 4), i64(sectionMap, q + 8)))
                q += 16
            }
            if (name == "AcDb:Security" || name == "AcDb:Classes" || name == "AcDb:Handles" || name == "AcDb:AcDbObjects" || name == "AcDb:AcDsPrototype_1b") {
                allMeta.add(RawSecMeta(name, size, maxSize, compressed, encrypted, pageList))
            }
        }
        allMeta.sortBy { if (it.name == "AcDb:Security") 0 else 1 }
        var anyEncrypted = false
        for (meta in allMeta) {
            val isSecMeta = meta.name == "AcDb:Security"
            val isEncrypted = !isSecMeta && ((meta.encrypted == 1) || (security and 1) != 0)
            if (isEncrypted) anyEncrypted = true
            if (isEncrypted && password.isNullOrEmpty()) {
                throw DwgFormatException("PASSWORD_REQUIRED: DWG drawing is encrypted or password protected.")
            }
            if (meta.size < 0 || meta.size > sectionLimit(bytes) || meta.maxSize <= 0 || meta.maxSize > 0x100000) {
                if (isSecMeta) continue
                throw DwgFormatException("DWG section ${meta.name} is too large")
            }
            val data = ByteArray(meta.size.toInt())
            for ((pageNumber, dataSize, startOffset) in meta.pageList) {
                val page = pages[pageNumber] ?: throw DwgFormatException("DWG page $pageNumber is missing")
                val a = page.address
                if (a < 0 || a + 32 > bytes.size) throw DwgFormatException("DWG page is outside the file")
                val ph = bytes.copyOfRange(a.toInt(), a.toInt() + 32)
                val secMask = (0x4164536b xor a.toInt())
                for (k in 0 until 8) {
                    val v = i32(ph, k * 4) xor secMask
                    ph[k * 4] = v.toByte(); ph[k * 4 + 1] = (v shr 8).toByte(); ph[k * 4 + 2] = (v shr 16).toByte(); ph[k * 4 + 3] = (v shr 24).toByte()
                }
                val compSize = i32(ph, 8)
                val start = (a + 32).toInt()
                val rawBytes = if (meta.compressed == 2) {
                    if (compSize < 0 || start + compSize > bytes.size) throw DwgFormatException("DWG page data is truncated")
                    bytes.copyOfRange(start, start + compSize)
                } else {
                    val n = minOf(maxOf(dataSize, meta.maxSize), bytes.size - start)
                    bytes.copyOfRange(start, start + n)
                }
                val pageBytes = if (isEncrypted) decryptDwgPage(rawBytes, securityParams, password!!) else rawBytes
                val payload = if (meta.compressed == 2) {
                    try {
                        decompressR2004(pageBytes, 0, pageBytes.size, meta.maxSize)
                    } catch (e: DwgFormatException) {
                        if (isEncrypted) throw DwgFormatException("PASSWORD_REQUIRED: Incorrect password or unsupported DWG encryption.")
                        throw e
                    }
                } else {
                    pageBytes
                }
                if (startOffset < 0 || startOffset >= data.size) continue
                val n = minOf(payload.size.toLong(), data.size - startOffset).toInt()
                System.arraycopy(payload, 0, data, startOffset.toInt(), n)
            }
            if (isSecMeta) {
                securityParams = parseDwgSecuritySection(data)
            } else {
                sections[meta.name] = data
            }
        }
        val handles = sections["AcDb:Handles"] ?: throw DwgFormatException("DWG handle map is missing")
        val objects = sections["AcDb:AcDbObjects"] ?: throw DwgFormatException("DWG object data is missing")
        val classes = sections["AcDb:Classes"]
        if (anyEncrypted && classes != null && (classes.size < 16 || !classes.copyOfRange(0, 16).contentEquals(CLASS_SENTINEL))) {
            throw DwgFormatException("PASSWORD_REQUIRED: Incorrect password or unsupported DWG encryption.")
        }
        return DwgSections(version, maintenance, codepage, classes, 0, handles, 0, handles.size, objects, sections["AcDb:AcDsPrototype_1b"])
    }

    private fun readSystemPage(bytes: ByteArray, address: Long, expectedType: Int): ByteArray {
        if (address < 0 || address + 20 > bytes.size) throw DwgFormatException("DWG system page is outside the file")
        val a = address.toInt()
        val type = i32(bytes, a)
        if (type != expectedType) throw DwgFormatException("DWG system page has wrong type")
        val decompSize = i32(bytes, a + 4)
        val compSize = i32(bytes, a + 8)
        if (decompSize < 0 || decompSize > MAX_SECTION || compSize < 0 || a + 20 + compSize > bytes.size) throw DwgFormatException("DWG system page is corrupted")
        return decompressR2004(bytes, a + 20, compSize, decompSize)
    }

    private val R21_COPY = arrayOf(
        intArrayOf(),
        intArrayOf(1, 0),
        intArrayOf(1, 1, 1, 0),
        intArrayOf(1, 2, 1, 1, 1, 0),
        intArrayOf(1, 0, 1, 1, 1, 2, 1, 3),
        intArrayOf(1, 4, 4, 0),
        intArrayOf(1, 5, 4, 1, 1, 0),
        intArrayOf(2, 5, 4, 1, 1, 0),
        intArrayOf(4, 0, 4, 4),
        intArrayOf(1, 8, 8, 0),
        intArrayOf(1, 9, 8, 1, 1, 0),
        intArrayOf(2, 9, 8, 1, 1, 0),
        intArrayOf(4, 8, 8, 0),
        intArrayOf(1, 12, 4, 8, 8, 0),
        intArrayOf(1, 13, 4, 9, 8, 1, 1, 0),
        intArrayOf(2, 13, 4, 9, 8, 1, 1, 0),
        intArrayOf(8, 8, 8, 0),
        intArrayOf(8, 9, 1, 8, 8, 0),
        intArrayOf(1, 17, 16, 1, 1, 0),
        intArrayOf(3, 16, 16, 0),
        intArrayOf(4, 16, 16, 0),
        intArrayOf(1, 20, 4, 16, 16, 0),
        intArrayOf(2, 20, 4, 16, 16, 0),
        intArrayOf(3, 20, 4, 16, 16, 0),
        intArrayOf(8, 16, 16, 0),
        intArrayOf(8, 17, 1, 16, 16, 0),
        intArrayOf(1, 25, 8, 17, 1, 16, 16, 0),
        intArrayOf(2, 25, 8, 17, 1, 16, 16, 0),
        intArrayOf(4, 24, 8, 16, 16, 0),
        intArrayOf(1, 28, 4, 24, 8, 16, 16, 0),
        intArrayOf(2, 28, 4, 24, 8, 16, 16, 0),
        intArrayOf(1, 30, 4, 26, 8, 18, 16, 2, 2, 0),
        intArrayOf(16, 16, 16, 0)
    )

    private fun r21CopyBlock(size: Int, src: ByteArray, s: Int, dst: ByteArray, d: Int) {
        if (size == 1) {
            dst[d] = src[s]
            return
        }
        val row = R21_COPY[size]
        var dp = d
        var i = 0
        while (i < row.size) {
            val n = row[i]
            r21CopyBlock(n, src, s + row[i + 1], dst, dp)
            dp += n
            i += 2
        }
    }

    fun decompressR21(src: ByteArray, srcStart: Int, srcLen: Int, dstSize: Int): ByteArray {
        val dst = ByteArray(dstSize)
        var s = srcStart
        val end = srcStart + srcLen
        var d = 0
        fun next(): Int {
            if (s >= end) throw DwgFormatException("R2007 compressed data is truncated")
            return src[s++].toInt() and 0xFF
        }
        var opcode = next()
        var length = 0
        if ((opcode and 0xF0) == 0x20) {
            s += 2
            length = next() and 0x07
        }
        var offset = 0
        fun readInstructions() {
            when (opcode shr 4) {
                0 -> {
                    length = (opcode and 0xF) + 0x13
                    offset = next()
                    opcode = next()
                    length += (opcode shr 3) and 0x10
                    offset += ((opcode and 0x78) shl 5) + 1
                }
                1 -> {
                    length = (opcode and 0xF) + 3
                    offset = next()
                    opcode = next()
                    offset += ((opcode and 0xF8) shl 5) + 1
                }
                2 -> {
                    offset = next()
                    offset = ((next() shl 8) and 0xFF00) or offset
                    length = opcode and 7
                    if ((opcode and 8) == 0) {
                        opcode = next()
                        length += opcode and 0xF8
                    } else {
                        offset++
                        length += next() shl 3
                        opcode = next()
                        length += ((opcode and 0xF8) shl 8) + 0x100
                    }
                }
                else -> {
                    length = opcode shr 4
                    offset = opcode and 15
                    opcode = next()
                    offset += ((opcode and 0xF8) shl 1) + 1
                }
            }
        }
        while (s < end) {
            if (length == 0) {
                length = opcode + 8
                if (length == 0x17) {
                    var n = next()
                    length += n
                    if (n == 0xFF) {
                        do {
                            n = next() or (next() shl 8)
                            length += n
                        } while (n == 0xFFFF)
                    }
                }
            }
            if (d + length > dstSize || s + length > end) throw DwgFormatException("R2007 literal overflows")
            var remaining = length
            while (remaining >= 32) {
                r21CopyBlock(32, src, s, dst, d)
                s += 32; d += 32; remaining -= 32
            }
            if (remaining > 0) {
                r21CopyBlock(remaining, src, s, dst, d)
                s += remaining; d += remaining
            }
            length = 0
            if (s >= end) break
            opcode = next()
            readInstructions()
            while (true) {
                if (offset > d || d + length > dstSize) throw DwgFormatException("R2007 back reference is invalid")
                for (i in 0 until length) dst[d + i] = dst[d - offset + i]
                d += length
                length = opcode and 7
                if (length != 0 || s >= end) break
                opcode = next()
                if ((opcode shr 4) == 0) break
                if ((opcode shr 4) == 0x0F) opcode = opcode and 0x0F
                readInstructions()
            }
        }
        return dst
    }

    private fun rsDecode(bytes: ByteArray, address: Long, dataLen: Int, k: Int, interleaved: Boolean, factor: Int = 1, fixedBlocks: Int = 0): ByteArray {
        val aligned = (dataLen + 7) and 7.inv()
        val total = aligned.toLong() * factor
        val blocks = if (fixedBlocks > 0) fixedBlocks else ((total + k - 1) / k).toInt().coerceAtLeast(1)
        val raw = blocks.toLong() * 255
        if (address < 0 || address + raw > bytes.size) throw DwgFormatException("R2007 page is outside the file")
        val a = address.toInt()
        val out = ByteArray(dataLen)
        if (interleaved) {
            var o = 0
            loop@ for (b in 0 until blocks) for (j in 0 until k) {
                if (o >= dataLen) break@loop
                out[o++] = bytes[a + j * blocks + b]
            }
        } else {
            System.arraycopy(bytes, a, out, 0, dataLen)
        }
        return out
    }

    private fun openR2007(bytes: ByteArray, version: DwgVersion, maintenance: Int, codepage: Int, password: String?): DwgSections {
        val security = i32(bytes, 0x18)
        if ((security and 1) != 0 && password.isNullOrEmpty()) {
            throw DwgFormatException("PASSWORD_REQUIRED: DWG drawing is encrypted or password protected.")
        }
        val header = rsDecode(bytes, 0x80, 3 * 239, 239, true, 1, 3)
        val comprLen = i32(header, 0x18)
        val fileHeader = if (comprLen < 0) {
            header.copyOfRange(0x20, 0x20 + minOf(-comprLen, header.size - 0x20))
        } else {
            if (0x20 + comprLen > header.size) throw DwgFormatException("R2007 file header is corrupted")
            decompressR21(header, 0x20, comprLen, 0x110)
        }
        val pagesMapCorrection = i64(fileHeader, 0x18)
        val pagesMapOffset = i64(fileHeader, 0x38)
        val pagesMapSizeCompressed = i64(fileHeader, 0x50)
        val pagesMapSizeUncompressed = i64(fileHeader, 0x58)
        val sectionsMapSizeCompressed = i64(fileHeader, 0xB0)
        val sectionsMapId = i64(fileHeader, 0xC0)
        val sectionsMapSizeUncompressed = i64(fileHeader, 0xC8)
        val sectionsMapCorrection = i64(fileHeader, 0xD8)
        if (pagesMapSizeUncompressed !in 1..MAX_SECTION || sectionsMapSizeUncompressed !in 1..MAX_SECTION) throw DwgFormatException("R2007 maps are invalid")
        val pageMap = loadR21SystemPage(bytes, pagesMapOffset + 0x480, pagesMapSizeCompressed.toInt(), pagesMapSizeUncompressed.toInt(), pagesMapCorrection.toInt())
        val pageAddress = HashMap<Long, Long>()
        var offset = 0L
        var p = 0
        while (p + 16 <= pageMap.size) {
            val size = i64(pageMap, p)
            val id = i64(pageMap, p + 8)
            p += 16
            pageAddress[if (id < 0) -id else id] = offset + 0x480
            offset += size
        }
        val sectionMapAddress = pageAddress[sectionsMapId] ?: throw DwgFormatException("R2007 section map is missing")
        val sectionMap = loadR21SystemPage(bytes, sectionMapAddress, sectionsMapSizeCompressed.toInt(), sectionsMapSizeUncompressed.toInt(), sectionsMapCorrection.toInt())
        val sections = HashMap<String, ByteArray>()
        class R21PageMeta(val pageOffset: Long, val pageId: Long, val uncompSize: Int, val compSize: Int)
        class R21SecMeta(val name: String, val dataSize: Long, val maxSize: Long, val encryption: Long, val encoding: Long, val pagesList: ArrayList<R21PageMeta>)
        val r21Metas = ArrayList<R21SecMeta>()
        var q = 0
        while (q + 64 <= sectionMap.size) {
            val dataSize = i64(sectionMap, q)
            val maxSize = i64(sectionMap, q + 8)
            val encryption = i64(sectionMap, q + 16)
            val nameLength = i64(sectionMap, q + 32).toInt()
            val encoding = i64(sectionMap, q + 48)
            val numPages = i64(sectionMap, q + 56).toInt()
            q += 64
            if (nameLength < 0 || nameLength > 512 || numPages < 0 || numPages > 1_000_000 || q + nameLength > sectionMap.size) throw DwgFormatException("R2007 section map is corrupted")
            val name = String(sectionMap, q, maxOf(0, nameLength - 2) and 1.inv(), StandardCharsets.UTF_16LE).trimEnd('\u0000')
            q += nameLength
            val wanted = name == "AcDb:Security" || name == "AcDb:Classes" || name == "AcDb:Handles" || name == "AcDb:AcDbObjects"
            val pList = ArrayList<R21PageMeta>()
            for (pg in 0 until numPages) {
                if (q + 56 > sectionMap.size) throw DwgFormatException("R2007 section map is truncated")
                val pageOffset = i64(sectionMap, q)
                val pageId = i64(sectionMap, q + 16)
                val uncompSize = i64(sectionMap, q + 24).toInt()
                val compSize = i64(sectionMap, q + 32).toInt()
                q += 56
                if (wanted) pList.add(R21PageMeta(pageOffset, pageId, uncompSize, compSize))
            }
            if (wanted) r21Metas.add(R21SecMeta(name, dataSize, maxSize, encryption, encoding, pList))
        }
        r21Metas.sortBy { if (it.name == "AcDb:Security") 0 else 1 }
        var secParams: DwgSecurityParams? = null
        var anyEncrypted = false
        for (meta in r21Metas) {
            val isSecMeta = meta.name == "AcDb:Security"
            if (meta.dataSize < 0 || meta.dataSize > sectionLimit(bytes) || meta.maxSize <= 0) {
                if (isSecMeta) continue
                throw DwgFormatException("R2007 section ${meta.name} is invalid")
            }
            val data = ByteArray(meta.dataSize.toInt())
            val isEnc = !isSecMeta && (meta.encryption == 1L || (security and 1) != 0)
            if (isEnc) anyEncrypted = true
            if (isEnc && password.isNullOrEmpty()) {
                throw DwgFormatException("PASSWORD_REQUIRED: DWG drawing is encrypted or password protected.")
            }
            for (pm in meta.pagesList) {
                val address = pageAddress[pm.pageId] ?: throw DwgFormatException("R2007 page ${pm.pageId} is missing")
                if (pm.compSize < 0 || pm.uncompSize < 0 || pm.uncompSize > 0x100000) throw DwgFormatException("R2007 page is corrupted")
                val decoded = rsDecode(bytes, address, pm.compSize, 251, meta.encoding == 4L)
                val srcBytes = if (isEnc) decryptDwgPage(decoded, secParams, password!!) else decoded
                val payload = try {
                    if (pm.compSize < pm.uncompSize) decompressR21(srcBytes, 0, minOf(pm.compSize, srcBytes.size), pm.uncompSize) else srcBytes
                } catch (e: DwgFormatException) {
                    if (isEnc) throw DwgFormatException("PASSWORD_REQUIRED: Incorrect password or unsupported DWG encryption.")
                    throw e
                }
                if (pm.pageOffset < 0 || pm.pageOffset >= data.size) continue
                val n = minOf(payload.size.toLong(), data.size - pm.pageOffset, pm.uncompSize.toLong()).toInt()
                System.arraycopy(payload, 0, data, pm.pageOffset.toInt(), n)
            }
            if (isSecMeta) secParams = parseDwgSecuritySection(data) else sections[meta.name] = data
        }
        val handles = sections["AcDb:Handles"] ?: throw DwgFormatException("DWG handle map is missing")
        val objects = sections["AcDb:AcDbObjects"] ?: throw DwgFormatException("DWG object data is missing")
        val classes = sections["AcDb:Classes"]
        if (anyEncrypted && classes != null && (classes.size < 16 || !classes.copyOfRange(0, 16).contentEquals(CLASS_SENTINEL))) {
            throw DwgFormatException("PASSWORD_REQUIRED: Incorrect password or unsupported DWG encryption.")
        }
        return DwgSections(version, maintenance, codepage, classes, 0, handles, 0, handles.size, objects)
    }

    private fun loadR21SystemPage(bytes: ByteArray, address: Long, compSize: Int, uncompSize: Int, factor: Int): ByteArray {
        if (compSize <= 0 || factor <= 0 || factor > 64) throw DwgFormatException("R2007 system page is invalid")
        val decoded = rsDecode(bytes, address, compSize, 239, true, factor)
        return if (compSize < uncompSize) decompressR21(decoded, 0, compSize, uncompSize) else decoded
    }

    fun readDataStore(ds: ByteArray): Map<Long, ByteArray> {
        val out = HashMap<Long, ByteArray>()
        val bb = java.nio.ByteBuffer.wrap(ds).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun i32(p: Int): Int { if (p < 0 || p + 4 > ds.size) throw DwgFormatException("DWG data store is truncated"); return bb.getInt(p) }
        fun i64(p: Int): Long { if (p < 0 || p + 8 > ds.size) throw DwgFormatException("DWG data store is truncated"); return bb.getLong(p) }
        if (ds.size < 56) return out
        val segIdxOffset = i32(24)
        val segCount = i32(32)
        val dataIdx = i32(40)
        if (segCount <= 0 || segCount > 1_000_000) return out
        val segStart = LongArray(segCount)
        val segSize = IntArray(segCount)
        val entries = segIdxOffset + 48
        for (k in 0 until segCount) {
            segStart[k] = i64(entries + k * 12)
            segSize[k] = i32(entries + k * 12 + 8)
        }
        if (dataIdx !in 0 until segCount) return out
        val di = segStart[dataIdx].toInt() + 48
        val count = i32(di)
        if (count < 0 || count > 10_000_000) throw DwgFormatException("DWG data store index is invalid")
        val bySegment = HashMap<Int, ArrayList<Int>>()
        for (k in 0 until count) {
            val seg = i32(di + 8 + k * 12)
            val local = i32(di + 8 + k * 12 + 4)
            if (seg <= 0 || seg >= segCount) continue
            bySegment.getOrPut(seg) { ArrayList() }.add(local)
        }
        for ((seg, locals) in bySegment) {
            val base = segStart[seg].toInt()
            val segEnd = base + segSize[seg]
            locals.sort()
            val headers = locals.map { lo ->
                val hp = base + 48 + lo
                longArrayOf(i32(hp).toLong(), i64(hp + 8), i32(hp + 16).toLong() and 0xFFFFFFFFL)
            }
            val dataStart = base + (i32(base + 36) shl 4)
            val starts = headers.map { dataStart + it[2].toInt() }.sorted()
            for (h in headers) {
                val rs = dataStart + h[2].toInt()
                val next = starts.firstOrNull { it > rs } ?: segEnd
                val maxSize = next - rs
                val dataSize = i32(rs)
                if (dataSize >= 0 && dataSize + 4 <= maxSize && rs + 4 + dataSize <= ds.size) {
                    out[h[1]] = ds.copyOfRange(rs + 4, rs + 4 + dataSize)
                } else if (dataSize == 0xbb106bb1.toInt()) {
                    val total = i64(rs + 4)
                    val pages = i32(rs + 12)
                    if (total < 0 || total > ds.size.toLong() * 4 || pages < 0 || pages > 100_000) continue
                    val buf = java.io.ByteArrayOutputStream()
                    for (pg in 0 until pages) {
                        val ps = i32(rs + 32 + pg * 8)
                        if (ps !in 0 until segCount) break
                        val bp = segStart[ps].toInt() + 48
                        val pageData = i64(bp + 24)
                        if (pageData < 0 || bp + 32 + pageData > ds.size) break
                        buf.write(ds, bp + 32, pageData.toInt())
                    }
                    out[h[1]] = buf.toByteArray()
                }
            }
        }
        return out
    }

    fun classSentinelAt(b: ByteArray, p: Int): Boolean =
        p >= 0 && p + 16 <= b.size && (0 until 16).all { b[p + it] == CLASS_SENTINEL[it] }

    private const val MAX_SECTION = 256L shl 20

    private fun sectionLimit(bytes: ByteArray): Long = minOf(MAX_SECTION, bytes.size.toLong() * 64 + (1L shl 20))
}
