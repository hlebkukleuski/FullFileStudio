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
 * Implements Office Document Cryptography ([MS-OFFCRYPTO] under the Microsoft Open Specifications
 * Promise) and OASIS OpenDocument Format (ODF 1.2 / 1.3, ISO/IEC 26300) package decryption,
 * informed by Apache POI POIFS/Crypt (https://poi.apache.org/, Copyright 2003-2026 The Apache
 * Software Foundation, Apache-2.0).
 */

package me.hlebkukleuski.fullfilestudio.core.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

data class DecryptedDocStreams(
    val wordDocument: ByteArray,
    val tableStream: ByteArray,
    val dataStream: ByteArray?
)

data class DecryptedPptStreams(
    val powerPointDocument: ByteArray,
    val currentUser: ByteArray?,
    val picturesStream: ByteArray?
)

object OfficeCrypto {
    const val DEFAULT_EXCEL_PASSWORD = "VelvetSweatshop"

    private val AGILE_VERIFIER_HASH_INPUT_BLOCK_KEY = byteArrayOf(
        0xFE.toByte(), 0xA7.toByte(), 0xD2.toByte(), 0x76, 0x3B, 0x4B, 0x9E.toByte(), 0x79
    )
    private val AGILE_VERIFIER_HASH_VALUE_BLOCK_KEY = byteArrayOf(
        0xD7.toByte(), 0xAA.toByte(), 0x0F, 0x6D, 0x30, 0x61, 0x34, 0x4E
    )
    private val AGILE_ENCRYPTED_KEY_VALUE_BLOCK_KEY = byteArrayOf(
        0x14, 0x6E, 0x0B, 0xE7.toByte(), 0xAB.toByte(), 0xAC.toByte(), 0xD0.toByte(), 0xD6.toByte()
    )

    private val XOR_PAD_ARRAY = byteArrayOf(
        0xBB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBA.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xB9.toByte(), 0x80.toByte(), 0x00, 0xBE.toByte(),
        0x0F, 0x00, 0xBF.toByte(), 0x0F, 0x00
    )

    private val XOR_INITIAL_CODE = intArrayOf(
        0xE1F0, 0x1D0F, 0xCC9C, 0x84C0, 0x110C,
        0x0E10, 0xF1CE, 0x313E, 0x1872, 0xE139,
        0xD40F, 0x84F9, 0x280C, 0xA96A, 0x4EC3
    )

    private val XOR_MATRIX = arrayOf(
        intArrayOf(0xAEFC, 0x4DD9, 0x9BB2, 0x2745, 0x4E8A, 0x9D14, 0x2A09),
        intArrayOf(0x7B61, 0xF6C2, 0xFDA5, 0xEB6B, 0xC6F7, 0x9DCF, 0x2BBF),
        intArrayOf(0x4563, 0x8AC6, 0x05AD, 0x0B5A, 0x16B4, 0x2D68, 0x5AD0),
        intArrayOf(0x0375, 0x06EA, 0x0DD4, 0x1BA8, 0x3750, 0x6EA0, 0xDD40),
        intArrayOf(0xD849, 0xA0B3, 0x5147, 0xA28E, 0x553D, 0xAA7A, 0x44D5),
        intArrayOf(0x6F45, 0xDE8A, 0xAD35, 0x4A4B, 0x9496, 0x390D, 0x721A),
        intArrayOf(0xEB23, 0xC667, 0x9CEF, 0x29FF, 0x53FE, 0xA7FC, 0x5FD9),
        intArrayOf(0x47D3, 0x8FA6, 0x0F6D, 0x1EDA, 0x3DB4, 0x7B68, 0xF6D0),
        intArrayOf(0xB861, 0x60E3, 0xC1C6, 0x93AD, 0x377B, 0x6EF6, 0xDDEC),
        intArrayOf(0x45A0, 0x8B40, 0x06A1, 0x0D42, 0x1A84, 0x3508, 0x6A10),
        intArrayOf(0xAA51, 0x4483, 0x8906, 0x022D, 0x045A, 0x08B4, 0x1168),
        intArrayOf(0x76B4, 0xED68, 0xCAF1, 0x85C3, 0x1BA7, 0x374E, 0x6E9C),
        intArrayOf(0x3730, 0x6E60, 0xDCC0, 0xA9A1, 0x4363, 0x86C6, 0x1DAD),
        intArrayOf(0x3331, 0x6662, 0xCCC4, 0x89A9, 0x0373, 0x06E6, 0x0DCC),
        intArrayOf(0x1021, 0x2042, 0x4084, 0x8108, 0x1231, 0x2462, 0x48C4)
    )

    class Rc4Cipher(key: ByteArray) {
        private val s = IntArray(256)
        private var i = 0
        private var j = 0

        init {
            for (k in 0 until 256) s[k] = k
            var jj = 0
            val kLen = key.size.coerceAtLeast(1)
            for (k in 0 until 256) {
                jj = (jj + s[k] + (key[k % kLen].toInt() and 0xFF)) and 0xFF
                val tmp = s[k]
                s[k] = s[jj]
                s[jj] = tmp
            }
        }

        fun skip(count: Int) {
            var k = 0
            while (k < count) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
                k++
            }
        }

        fun process(data: ByteArray, offset: Int = 0, len: Int = data.size - offset): ByteArray {
            val out = ByteArray(len)
            for (k in 0 until len) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
                val ks = s[(s[i] + s[j]) and 0xFF]
                out[k] = ((data[offset + k].toInt() and 0xFF) xor ks).toByte()
            }
            return out
        }

        fun processInPlace(data: ByteArray, offset: Int = 0, len: Int = data.size - offset) {
            for (k in 0 until len) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
                val ks = s[(s[i] + s[j]) and 0xFF]
                data[offset + k] = ((data[offset + k].toInt() and 0xFF) xor ks).toByte()
            }
        }
    }

    private fun u16(b: ByteArray, p: Int): Int {
        if (p < 0 || p + 2 > b.size) return 0
        return (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    }

    private fun i32(b: ByteArray, p: Int): Int {
        if (p < 0 || p + 4 > b.size) return 0
        return (b[p].toInt() and 0xFF) or
            ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or
            ((b[p + 3].toInt() and 0xFF) shl 24)
    }

    private fun i64(b: ByteArray, p: Int): Long {
        if (p < 0 || p + 8 > b.size) return 0L
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[p + k].toLong() and 0xFF)
        return v
    }

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun digest(alg: String, vararg chunks: ByteArray): ByteArray {
        val md = MessageDigest.getInstance(alg)
        for (c in chunks) md.update(c)
        return md.digest()
    }

    private fun jcaHashName(name: String): String = when (name.uppercase(Locale.ROOT).replace("-", "")) {
        "SHA1" -> "SHA-1"
        "SHA256" -> "SHA-256"
        "SHA384" -> "SHA-384"
        "SHA512" -> "SHA-512"
        "MD5" -> "MD5"
        else -> "SHA-256"
    }

    private fun adjustLength(src: ByteArray, targetLen: Int, padByte: Byte): ByteArray {
        if (src.size == targetLen) return src
        val out = ByteArray(targetLen)
        val n = minOf(src.size, targetLen)
        System.arraycopy(src, 0, out, 0, n)
        for (k in n until targetLen) out[k] = padByte
        return out
    }

    private fun aesCipherName(chaining: String): String = when {
        chaining.endsWith("CFB", ignoreCase = true) -> "AES/CFB/NoPadding"
        chaining.endsWith("ECB", ignoreCase = true) -> "AES/ECB/NoPadding"
        else -> "AES/CBC/NoPadding"
    }

    private fun aesDecrypt(key: ByteArray, iv: ByteArray?, data: ByteArray, cipherTransform: String): ByteArray {
        val cipher = Cipher.getInstance(cipherTransform)
        val keySpec = SecretKeySpec(key, "AES")
        if (iv != null && !cipherTransform.contains("ECB")) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
        }
        return cipher.doFinal(data)
    }

    private fun parseSafeXml(xmlBytes: ByteArray): Document? {
        return try {
            val preview = String(xmlBytes, 0, minOf(xmlBytes.size, 65536), Charsets.UTF_8).lowercase(java.util.Locale.ROOT)
            if (preview.contains("<!doctype") || preview.contains("<!entity")) {
                return null
            }
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = true
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Throwable) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
            try { dbf.isXIncludeAware = false } catch (_: Throwable) {}
            try { dbf.isExpandEntityReferences = false } catch (_: Throwable) {}
            val builder = dbf.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
            builder.parse(ByteArrayInputStream(xmlBytes))
        } catch (_: Throwable) {
            null
        }
    }

    private fun elementAttr(el: Element, localName: String): String? {
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val item = attrs.item(i)
            val n = item.localName ?: item.nodeName?.substringAfterLast(':') ?: continue
            if (n.equals(localName, ignoreCase = true)) return item.nodeValue
        }
        return null
    }

    private fun decodeBase64(s: String?): ByteArray? {
        if (s.isNullOrBlank()) return null
        return try {
            Base64.getMimeDecoder().decode(s.trim())
        } catch (_: Throwable) {
            null
        }
    }

    const val OFFICE_IRM_DRM_ERROR =
        "DRM_PROTECTED: This Microsoft Office document is protected by Information Rights Management (IRM/RMS) DRM. Circumventing DRM protection is prohibited under DMCA § 1201 and international copyright law."

    internal fun isOfficeIrmProtected(cfb: Ole2CfbContainer): Boolean {
        if (!cfb.isValid) return false
        for (entry in cfb.listStreams()) {
            val clean = entry.name.trimStart { it.code < 0x20 }.lowercase(Locale.ROOT)
            if (clean == "drmcontent" ||
                clean == "drmencrypteddataspace" ||
                clean == "drmencryptedtransform" ||
                clean == "drmdataspace" ||
                clean == "drmtransform" ||
                clean.contains("drmencrypted")
            ) {
                return true
            }
        }
        val dsMap = cfb.readStream("DataSpaceMap")
        if (dsMap != null) {
            val utf16 = try { String(dsMap, StandardCharsets.UTF_16LE) } catch (_: Throwable) { "" }
            val ascii = try { String(dsMap, StandardCharsets.ISO_8859_1) } catch (_: Throwable) { "" }
            if (utf16.contains("DRMEncrypted", ignoreCase = true) ||
                ascii.contains("DRMEncrypted", ignoreCase = true) ||
                utf16.contains("DRMDataSpace", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    fun isOfficeIrmBytes(oleBytes: ByteArray): Boolean {
        if (oleBytes.size < 512) return false
        val cfb = Ole2CfbContainer(oleBytes)
        return isOfficeIrmProtected(cfb)
    }

    internal fun isEncryptedOoxml(cfb: Ole2CfbContainer): Boolean =
        cfb.isValid && cfb.readStream("EncryptionInfo") != null && cfb.readStream("EncryptedPackage") != null

    fun decryptOoxmlBytes(
        oleBytes: ByteArray,
        password: String?,
        tryDefaultExcelPassword: Boolean = false
    ): ByteArray? {
        val cfb = Ole2CfbContainer(oleBytes)
        if (isOfficeIrmProtected(cfb)) throw SecurityException(OFFICE_IRM_DRM_ERROR)
        if (!isEncryptedOoxml(cfb)) return null
        if (password != null) {
            decryptOoxml(cfb, password)?.let { return it }
        }
        if (tryDefaultExcelPassword && password != DEFAULT_EXCEL_PASSWORD) {
            decryptOoxml(cfb, DEFAULT_EXCEL_PASSWORD)?.let { return it }
        }
        return null
    }

    internal fun decryptOoxml(cfb: Ole2CfbContainer, password: String): ByteArray? {
        val info = cfb.readStream("EncryptionInfo") ?: return null
        val pkg = cfb.readStream("EncryptedPackage") ?: return null
        if (info.size < 12 || pkg.size < 8) return null
        val vMajor = u16(info, 0)
        val vMinor = u16(info, 2)
        return when {
            vMajor == 4 && vMinor == 4 -> decryptOoxmlAgile(info, pkg, password)
            vMajor in 2..4 && vMinor == 2 -> decryptOoxmlStandard(info, pkg, password)
            vMajor in 3..4 && vMinor == 3 -> throw SecurityException("UNSUPPORTED_ENCRYPTION: This Office document uses Extensible Encryption, which is not supported.")
            else -> throw SecurityException("UNSUPPORTED_ENCRYPTION: This Office document uses an unsupported EncryptionInfo version ($vMajor.$vMinor).")
        }
    }

    private class AgileParams(
        val saltSize: Int,
        val blockSize: Int,
        val keyBits: Int,
        val hashSize: Int,
        val cipherChaining: String,
        val hashAlg: String,
        val salt: ByteArray,
        val spinCount: Int = 0,
        val encVerifierHashInput: ByteArray? = null,
        val encVerifierHashValue: ByteArray? = null,
        val encKeyValue: ByteArray? = null
    )

    private fun decryptOoxmlAgile(info: ByteArray, pkg: ByteArray, password: String): ByteArray? {
        var xmlEnd = info.size
        while (xmlEnd > 8 && (info[xmlEnd - 1].toInt() == 0 || info[xmlEnd - 1].toInt() == ' '.code || info[xmlEnd - 1].toInt() == '\r'.code || info[xmlEnd - 1].toInt() == '\n'.code)) {
            xmlEnd--
        }
        if (xmlEnd <= 8) return null
        val xmlBytes = info.copyOfRange(8, xmlEnd)
        val doc = parseSafeXml(xmlBytes) ?: return null
        var keyData: AgileParams? = null
        var pwdEncryptor: AgileParams? = null
        val allNodes = doc.getElementsByTagName("*")
        for (idx in 0 until allNodes.length) {
            val el = allNodes.item(idx) as? Element ?: continue
            val local = el.localName ?: el.nodeName?.substringAfterLast(':') ?: ""
            if (local == "keyData") {
                val cipherAlg = elementAttr(el, "cipherAlgorithm") ?: "AES"
                if (!cipherAlg.startsWith("AES", ignoreCase = true)) {
                    throw SecurityException("UNSUPPORTED_ENCRYPTION: This Office document uses a non-AES Agile cipher ($cipherAlg) that is not supported.")
                }
                val salt = decodeBase64(elementAttr(el, "saltValue"))
                if (salt != null) {
                    keyData = AgileParams(
                        saltSize = elementAttr(el, "saltSize")?.toIntOrNull() ?: salt.size,
                        blockSize = elementAttr(el, "blockSize")?.toIntOrNull() ?: 16,
                        keyBits = elementAttr(el, "keyBits")?.toIntOrNull() ?: 128,
                        hashSize = elementAttr(el, "hashSize")?.toIntOrNull() ?: 32,
                        cipherChaining = elementAttr(el, "cipherChaining") ?: "ChainingModeCBC",
                        hashAlg = jcaHashName(elementAttr(el, "hashAlgorithm") ?: "SHA256"),
                        salt = salt
                    )
                }
            } else if (local == "encryptedKey") {
                val cipherAlg = elementAttr(el, "cipherAlgorithm") ?: "AES"
                if (!cipherAlg.startsWith("AES", ignoreCase = true)) {
                    throw SecurityException("UNSUPPORTED_ENCRYPTION: This Office document uses a non-AES Agile cipher ($cipherAlg) that is not supported.")
                }
                val salt = decodeBase64(elementAttr(el, "saltValue"))
                val vInput = decodeBase64(elementAttr(el, "encryptedVerifierHashInput"))
                val vValue = decodeBase64(elementAttr(el, "encryptedVerifierHashValue"))
                val kVal = decodeBase64(elementAttr(el, "encryptedKeyValue"))
                if (salt != null && vInput != null && vValue != null && kVal != null) {
                    pwdEncryptor = AgileParams(
                        saltSize = elementAttr(el, "saltSize")?.toIntOrNull() ?: salt.size,
                        blockSize = elementAttr(el, "blockSize")?.toIntOrNull() ?: 16,
                        keyBits = elementAttr(el, "keyBits")?.toIntOrNull() ?: 128,
                        hashSize = elementAttr(el, "hashSize")?.toIntOrNull() ?: 32,
                        cipherChaining = elementAttr(el, "cipherChaining") ?: "ChainingModeCBC",
                        hashAlg = jcaHashName(elementAttr(el, "hashAlgorithm") ?: "SHA256"),
                        salt = salt,
                        spinCount = elementAttr(el, "spinCount")?.toIntOrNull() ?: 100000,
                        encVerifierHashInput = vInput,
                        encVerifierHashValue = vValue,
                        encKeyValue = kVal
                    )
                }
            }
        }

        val kd = keyData ?: return null
        val pe = pwdEncryptor ?: throw SecurityException("UNSUPPORTED_ENCRYPTION: Certificate-encrypted Office document without a password encryptor is not supported.")
        if (pe.spinCount < 0 || pe.spinCount > 5_000_000) return null

        val pwdBytes = password.toByteArray(StandardCharsets.UTF_16LE)
        val md = MessageDigest.getInstance(pe.hashAlg)
        md.update(pe.salt)
        md.update(pwdBytes)
        var h = md.digest()
        val iterBuf = ByteArray(4)
        for (i in 0 until pe.spinCount) {
            iterBuf[0] = (i and 0xFF).toByte()
            iterBuf[1] = ((i ushr 8) and 0xFF).toByte()
            iterBuf[2] = ((i ushr 16) and 0xFF).toByte()
            iterBuf[3] = ((i ushr 24) and 0xFF).toByte()
            md.reset()
            md.update(iterBuf)
            md.update(h)
            h = md.digest()
        }

        val peKeyLen = (pe.keyBits / 8).coerceIn(16, 32)
        val peIv = adjustLength(pe.salt, pe.blockSize, 0x36)
        val peTransform = aesCipherName(pe.cipherChaining)

        fun derivePeKey(blockKey: ByteArray): ByteArray {
            md.reset()
            md.update(h)
            md.update(blockKey)
            return adjustLength(md.digest(), peKeyLen, 0x36)
        }

        val decryptedVInput = try {
            aesDecrypt(derivePeKey(AGILE_VERIFIER_HASH_INPUT_BLOCK_KEY), peIv, pe.encVerifierHashInput!!, peTransform)
        } catch (_: Throwable) {
            return null
        }
        val decryptedVValue = try {
            aesDecrypt(derivePeKey(AGILE_VERIFIER_HASH_VALUE_BLOCK_KEY), peIv, pe.encVerifierHashValue!!, peTransform)
        } catch (_: Throwable) {
            return null
        }

        val hashLen = minOf(pe.hashSize, decryptedVValue.size)
        val calcHashFull = digest(pe.hashAlg, decryptedVInput)
        val calcHashTrim = if (pe.saltSize in 1 until decryptedVInput.size) {
            digest(pe.hashAlg, decryptedVInput.copyOf(pe.saltSize))
        } else calcHashFull

        val matches = (0 until hashLen).all { calcHashTrim[it] == decryptedVValue[it] } ||
            (0 until hashLen).all { calcHashFull[it] == decryptedVValue[it] }
        if (!matches) return null

        val rawSecretKey = try {
            aesDecrypt(derivePeKey(AGILE_ENCRYPTED_KEY_VALUE_BLOCK_KEY), peIv, pe.encKeyValue!!, peTransform)
        } catch (_: Throwable) {
            return null
        }
        val secretKeyLen = (kd.keyBits / 8).coerceIn(16, 32)
        val secretKey = rawSecretKey.copyOf(secretKeyLen)

        val totalSize = i64(pkg, 0)
        if (totalSize < 0L || totalSize > 256L * 1024L * 1024L) return null
        val out = ByteArray(totalSize.toInt())
        val kdTransform = aesCipherName(kd.cipherChaining)
        val kdMd = MessageDigest.getInstance(kd.hashAlg)
        var inPos = 8
        var outPos = 0
        var segIdx = 0
        while (outPos < out.size && inPos < pkg.size) {
            val segLen = minOf(4096, pkg.size - inPos)
            val alignedLen = segLen - (segLen % kd.blockSize)
            if (alignedLen <= 0) break
            kdMd.reset()
            kdMd.update(kd.salt)
            kdMd.update(le32(segIdx))
            val segIv = adjustLength(kdMd.digest(), kd.blockSize, 0x36)
            val dec = try {
                aesDecrypt(secretKey, segIv, pkg.copyOfRange(inPos, inPos + alignedLen), kdTransform)
            } catch (_: Throwable) {
                return null
            }
            val toCopy = minOf(dec.size, out.size - outPos)
            System.arraycopy(dec, 0, out, outPos, toCopy)
            inPos += 4096
            outPos += toCopy
            segIdx++
        }
        return out
    }

    private fun decryptOoxmlStandard(info: ByteArray, pkg: ByteArray, password: String): ByteArray? {
        val topFlags = i32(info, 4)
        if ((topFlags and 0x10) != 0) throw SecurityException(OFFICE_IRM_DRM_ERROR)
        val headerSize = i32(info, 8)
        if (headerSize < 32 || 12 + headerSize + 40 > info.size) return null
        val headerFlags = i32(info, 12)
        if ((headerFlags and 0x10) != 0) throw SecurityException(OFFICE_IRM_DRM_ERROR)
        val algId = i32(info, 12 + 8)
        if (algId != 0 && algId !in setOf(0x0000660E, 0x0000660F, 0x00006610)) {
            throw SecurityException("UNSUPPORTED_ENCRYPTION: This Office document uses a non-AES standard encryption algorithm.")
        }
        var keyBits = i32(info, 12 + 16)
        if (keyBits == 0) keyBits = when (algId) {
            0x0000660F -> 192
            0x00006610 -> 256
            else -> 128
        }
        val vOff = 12 + headerSize
        val saltSize = i32(info, vOff)
        if (saltSize != 16 || vOff + 4 + 16 + 16 + 4 + 32 > info.size) return null
        val salt = info.copyOfRange(vOff + 4, vOff + 20)
        val encVerifier = info.copyOfRange(vOff + 20, vOff + 36)
        val verifierHashSize = i32(info, vOff + 36).coerceIn(1, 32)
        val encVerifierHash = info.copyOfRange(vOff + 40, vOff + 72)

        val md = MessageDigest.getInstance("SHA-1")
        md.update(salt)
        md.update(password.toByteArray(StandardCharsets.UTF_16LE))
        var h = md.digest()
        val iterBuf = ByteArray(4)
        for (i in 0 until 50_000) {
            iterBuf[0] = (i and 0xFF).toByte()
            iterBuf[1] = ((i ushr 8) and 0xFF).toByte()
            iterBuf[2] = ((i ushr 16) and 0xFF).toByte()
            iterBuf[3] = ((i ushr 24) and 0xFF).toByte()
            md.reset()
            md.update(iterBuf)
            md.update(h)
            h = md.digest()
        }
        md.reset()
        md.update(h)
        md.update(le32(0))
        val hFinal = md.digest()

        val keyLen = (keyBits / 8).coerceIn(16, 32)
        val key = cryptoApiDeriveKey(hFinal, keyLen * 8, algId)
        val verifier = try {
            aesDecrypt(key, null, encVerifier, "AES/ECB/NoPadding")
        } catch (_: Throwable) {
            return null
        }
        val verifierHash = try {
            aesDecrypt(key, null, encVerifierHash, "AES/ECB/NoPadding")
        } catch (_: Throwable) {
            return null
        }
        val expected = digest("SHA-1", verifier)
        val cmpLen = minOf(verifierHashSize, expected.size, verifierHash.size)
        if (!(0 until cmpLen).all { expected[it] == verifierHash[it] }) return null

        val totalSize = i64(pkg, 0)
        if (totalSize < 0L || totalSize > 256L * 1024L * 1024L) return null
        val encBodyLen = ((pkg.size - 8) / 16) * 16
        if (encBodyLen < totalSize) return null
        val dec = try {
            aesDecrypt(key, null, pkg.copyOfRange(8, 8 + encBodyLen), "AES/ECB/NoPadding")
        } catch (_: Throwable) {
            return null
        }
        return dec.copyOf(totalSize.toInt())
    }

    fun cryptoApiDeriveKey(hash: ByteArray, keyBits: Int, algId: Int): ByteArray {
        val keyLen = (keyBits / 8).coerceAtLeast(5)
        if (algId == 0x00006801) {
            return if (keyLen == 5) {
                val out = ByteArray(16)
                System.arraycopy(hash, 0, out, 0, minOf(5, hash.size))
                out
            } else {
                hash.copyOf(minOf(keyLen, hash.size))
            }
        }
        val buf1 = ByteArray(64) { 0x36 }
        val buf2 = ByteArray(64) { 0x5C }
        val n = minOf(hash.size, 64)
        for (i in 0 until n) {
            buf1[i] = (buf1[i].toInt() xor hash[i].toInt()).toByte()
            buf2[i] = (buf2[i].toInt() xor hash[i].toInt()).toByte()
        }
        val x1 = digest("SHA-1", buf1)
        val x2 = digest("SHA-1", buf2)
        val combined = ByteArray(x1.size + x2.size)
        System.arraycopy(x1, 0, combined, 0, x1.size)
        System.arraycopy(x2, 0, combined, x1.size, x2.size)
        return combined.copyOf(keyLen)
    }

    private fun deriveXorKeyAndVerifier(password: String): Triple<Int, Int, ByteArray>? {
        if (password.isEmpty()) return null
        val pwd = password.toByteArray(StandardCharsets.ISO_8859_1).let {
            if (it.size > 15) it.copyOf(15) else it
        }
        val n = pwd.size
        var verifier = 0
        for (i in n - 1 downTo 0) {
            val high = if ((verifier and 0x4000) != 0) 1 else 0
            verifier = (((verifier shl 1) and 0x7FFF) or high) xor (pwd[i].toInt() and 0xFF)
        }
        val highN = if ((verifier and 0x4000) != 0) 1 else 0
        verifier = ((((verifier shl 1) and 0x7FFF) or highN) xor n) xor 0xCE4B

        var xorKey = XOR_INITIAL_CODE[n - 1]
        var cur = 15
        for (i in n - 1 downTo 0) {
            cur--
            var ch = pwd[i].toInt() and 0xFF
            for (bit in 6 downTo 0) {
                if ((ch and 0x40) != 0) xorKey = xorKey xor XOR_MATRIX[cur][bit]
                ch = ch shl 1
            }
        }
        val obf = ByteArray(16)
        System.arraycopy(pwd, 0, obf, 0, n)
        if (n < 16) {
            System.arraycopy(XOR_PAD_ARRAY, 0, obf, n, 16 - n)
        }
        val lo = xorKey and 0xFF
        val hi = (xorKey ushr 8) and 0xFF
        for (i in 0 until 16 step 2) {
            val b0 = (obf[i].toInt() and 0xFF) xor lo
            val b1 = (obf[i + 1].toInt() and 0xFF) xor hi
            obf[i] = (((b0 shl 2) or (b0 ushr 6)) and 0xFF).toByte()
            obf[i + 1] = (((b1 shl 2) or (b1 ushr 6)) and 0xFF).toByte()
        }
        return Triple(xorKey, verifier, obf)
    }

    private fun parseBinaryRc4KeyGen(encHeader: ByteArray, password: String): ((Int) -> ByteArray)? {
        if (encHeader.size < 52) return null
        val salt = encHeader.copyOfRange(4, 20)
        val encVerifier = encHeader.copyOfRange(20, 36)
        val encVerifierHash = encHeader.copyOfRange(36, 52)
        val truncPwd = if (password.length > 255) password.substring(0, 255) else password
        val h0 = digest("MD5", truncPwd.toByteArray(StandardCharsets.UTF_16LE))
        val truncH = h0.copyOf(5)
        val buf = ByteArray(16 * 21)
        for (i in 0 until 16) {
            System.arraycopy(truncH, 0, buf, i * 21, 5)
            System.arraycopy(salt, 0, buf, i * 21 + 5, 16)
        }
        val intermediate = digest("MD5", buf).copyOf(5)
        val keyForBlock: (Int) -> ByteArray = { block -> digest("MD5", intermediate, le32(block)) }

        val rc4 = Rc4Cipher(keyForBlock(0))
        val verifier = rc4.process(encVerifier)
        val verifierHash = rc4.process(encVerifierHash)
        val expected = digest("MD5", verifier)
        if (!expected.contentEquals(verifierHash)) return null
        return keyForBlock
    }

    private fun parseCryptoApiRc4KeyGen(encHeader: ByteArray, password: String): ((Int) -> ByteArray)? {
        if (encHeader.size < 44) return null
        val headerSize = i32(encHeader, 8)
        if (headerSize < 32 || 12 + headerSize + 40 > encHeader.size) return null
        var keyBits = i32(encHeader, 12 + 16)
        if (keyBits == 0) keyBits = 40
        val vOff = 12 + headerSize
        val saltSize = i32(encHeader, vOff)
        if (saltSize != 16 || vOff + 4 + 16 + 16 + 4 + 20 > encHeader.size) return null
        val salt = encHeader.copyOfRange(vOff + 4, vOff + 20)
        val encVerifier = encHeader.copyOfRange(vOff + 20, vOff + 36)
        val verifierHashSize = i32(encHeader, vOff + 36).coerceIn(1, 20)
        val encVerifierHash = encHeader.copyOfRange(vOff + 40, vOff + 40 + verifierHashSize)

        val h0 = digest("SHA-1", salt, password.toByteArray(StandardCharsets.UTF_16LE))
        val keyLen = (keyBits / 8).coerceIn(5, 16)
        val keyForBlock: (Int) -> ByteArray = { block ->
            val hb = digest("SHA-1", h0, le32(block))
            if (keyLen == 5) {
                val padded = ByteArray(16)
                System.arraycopy(hb, 0, padded, 0, 5)
                padded
            } else {
                hb.copyOf(keyLen)
            }
        }

        val rc4 = Rc4Cipher(keyForBlock(0))
        val verifier = rc4.process(encVerifier)
        val verifierHash = rc4.process(encVerifierHash)
        val expected = digest("SHA-1", verifier)
        if (!(0 until verifierHashSize).all { expected[it] == verifierHash[it] }) return null
        return keyForBlock
    }

    private fun decryptStreamRc4Blocks(
        src: ByteArray,
        plainPrefixBytes: Int,
        blockSize: Int,
        keyForBlock: (Int) -> ByteArray
    ): ByteArray {
        val out = src.copyOf()
        var pos = 0
        var blockIdx = 0
        while (pos < out.size) {
            val end = minOf(out.size, pos + blockSize)
            val rc4 = Rc4Cipher(keyForBlock(blockIdx))
            if (end <= plainPrefixBytes) {
                pos = end
                blockIdx++
                continue
            }
            val startDec = maxOf(pos, plainPrefixBytes)
            val skip = startDec - pos
            if (skip > 0) rc4.skip(skip)
            rc4.processInPlace(out, startDec, end - startDec)
            pos = end
            blockIdx++
        }
        return out
    }

    fun isEncryptedDoc(wordDoc: ByteArray): Boolean {
        if (wordDoc.size < 68) return false
        return (u16(wordDoc, 10) and 0x0100) != 0
    }

    fun decryptDoc(
        wordDoc: ByteArray,
        tableStream: ByteArray?,
        dataStream: ByteArray?,
        password: String
    ): DecryptedDocStreams? {
        if (wordDoc.size < 68 || tableStream == null) return null
        val flags = u16(wordDoc, 10)
        if ((flags and 0x0100) == 0) return DecryptedDocStreams(wordDoc, tableStream, dataStream)
        val isObfuscated = (flags and 0x8000) != 0
        if (isObfuscated) {
            val fileKey = u16(wordDoc, 14)
            val fileHash = u16(wordDoc, 16)
            val derived = deriveXorKeyAndVerifier(password) ?: return null
            if (derived.first != fileKey || derived.second != fileHash) return null
            val obf = derived.third
            fun xorDocStream(src: ByteArray, plainPrefix: Int): ByteArray {
                val out = src.copyOf()
                for (k in plainPrefix until out.size) {
                    val b = out[k].toInt() and 0xFF
                    val x = obf[k and 0x0F].toInt() and 0xFF
                    if (b != 0 && b != x) out[k] = (b xor x).toByte()
                }
                return out
            }
            val decWord = xorDocStream(wordDoc, 68)
            clearDocEncryptedFlags(decWord)
            return DecryptedDocStreams(
                wordDocument = decWord,
                tableStream = xorDocStream(tableStream, 0),
                dataStream = dataStream?.let { xorDocStream(it, 0) }
            )
        }

        val headerLen = i32(wordDoc, 14).coerceIn(0, tableStream.size)
        if (tableStream.size < 52) return null
        val vMajor = u16(tableStream, 0)
        val vMinor = u16(tableStream, 2)
        val keyGen = when {
            vMajor == 1 && vMinor == 1 -> parseBinaryRc4KeyGen(tableStream, password)
            vMajor in 2..4 && vMinor == 2 -> parseCryptoApiRc4KeyGen(tableStream, password)
            else -> null
        } ?: return null

        val decWord = decryptStreamRc4Blocks(wordDoc, 68, 512, keyGen)
        clearDocEncryptedFlags(decWord)
        val decTable = decryptStreamRc4Blocks(tableStream, headerLen, 512, keyGen)
        val decData = dataStream?.let { decryptStreamRc4Blocks(it, 0, 512, keyGen) }
        return DecryptedDocStreams(decWord, decTable, decData)
    }

    private fun clearDocEncryptedFlags(wordDoc: ByteArray) {
        val flags = u16(wordDoc, 10) and 0x0100.inv() and 0x8000.inv()
        wordDoc[10] = (flags and 0xFF).toByte()
        wordDoc[11] = ((flags ushr 8) and 0xFF).toByte()
    }

    fun isEncryptedXls(workbook: ByteArray): Boolean {
        var pos = 0
        while (pos + 4 <= workbook.size) {
            val recId = u16(workbook, pos)
            val recLen = u16(workbook, pos + 2)
            if (pos + 4 + recLen > workbook.size) break
            if (recId == 0x002F) return true
            if (recId == 0x000A) break
            pos += 4 + recLen
        }
        return false
    }

    fun decryptXlsWithDefaultFallback(workbook: ByteArray, password: String?): ByteArray? {
        if (!isEncryptedXls(workbook)) return workbook
        if (password != null) {
            decryptXls(workbook, password)?.let { return it }
        }
        if (password != DEFAULT_EXCEL_PASSWORD) {
            decryptXls(workbook, DEFAULT_EXCEL_PASSWORD)?.let { return it }
        }
        return null
    }

    fun decryptXls(workbook: ByteArray, password: String): ByteArray? {
        var filePassOffset = -1
        var filePassLen = 0
        var scan = 0
        while (scan + 4 <= workbook.size) {
            val recId = u16(workbook, scan)
            val recLen = u16(workbook, scan + 2)
            if (scan + 4 + recLen > workbook.size) break
            if (recId == 0x002F) {
                filePassOffset = scan
                filePassLen = recLen
                break
            }
            if (recId == 0x000A) break
            scan += 4 + recLen
        }
        if (filePassOffset < 0 || filePassLen < 6) return null
        val fpStart = filePassOffset + 4
        val encType = u16(workbook, fpStart)
        if (encType == 0x0000) {
            val fileKey = u16(workbook, fpStart + 2)
            val fileVerifier = u16(workbook, fpStart + 4)
            val derived = deriveXorKeyAndVerifier(password) ?: return null
            if (derived.first != fileKey || derived.second != fileVerifier) return null
            val obf = derived.third
            val out = workbook.copyOf()
            var pos = filePassOffset + 4 + filePassLen
            while (pos + 4 <= out.size) {
                val recId = u16(out, pos)
                val recLen = u16(out, pos + 2)
                val body = pos + 4
                if (body + recLen > out.size) break
                val skip = when (recId) {
                    0x0809, 0x002F, 0x00E1 -> recLen
                    0x0085 -> minOf(4, recLen)
                    else -> 0
                }
                if (skip < recLen) {
                    var idx = (body + recLen) and 0x0F
                    for (k in 0 until recLen) {
                        if (k >= skip) {
                            val b = out[body + k].toInt() and 0xFF
                            val rot = ((b shl 5) or (b ushr 3)) and 0xFF
                            out[body + k] = (rot xor (obf[idx].toInt() and 0xFF)).toByte()
                        }
                        idx = (idx + 1) and 0x0F
                    }
                }
                pos = body + recLen
            }
            neutralizeFilePassRecord(out, filePassOffset)
            return out
        }

        if (encType != 0x0001 || filePassLen < 54) return null
        val encInfo = workbook.copyOfRange(fpStart + 2, fpStart + filePassLen)
        val vMajor = u16(encInfo, 0)
        val vMinor = u16(encInfo, 2)
        val keyGen = when {
            vMajor == 1 && vMinor == 1 -> parseBinaryRc4KeyGen(encInfo, password)
            vMajor in 2..4 && vMinor == 2 -> parseCryptoApiRc4KeyGen(encInfo, password)
            else -> null
        } ?: return null

        val out = workbook.copyOf()
        val blockSize = 1024
        var currentBlock = -1
        var rc4 = Rc4Cipher(keyGen(0))
        var streamPos = 0

        fun seekAndConsume(targetPos: Int, decryptRangeStart: Int, decryptRangeEnd: Int) {
            var p = streamPos
            while (p < targetPos) {
                val blk = p / blockSize
                if (blk != currentBlock) {
                    currentBlock = blk
                    rc4 = Rc4Cipher(keyGen(blk))
                    val offInBlk = p - blk * blockSize
                    if (offInBlk > 0) rc4.skip(offInBlk)
                }
                val blkEnd = minOf(targetPos, (blk + 1) * blockSize)
                val segEnd = if (p < decryptRangeStart) {
                    minOf(blkEnd, decryptRangeStart)
                } else if (p < decryptRangeEnd) {
                    minOf(blkEnd, decryptRangeEnd)
                } else {
                    blkEnd
                }
                val segLen = segEnd - p
                if (p >= decryptRangeStart && p < decryptRangeEnd) {
                    rc4.processInPlace(out, p, segLen)
                } else {
                    rc4.skip(segLen)
                }
                p = segEnd
            }
            streamPos = targetPos
        }

        var pos = 0
        while (pos + 4 <= out.size) {
            val recId = u16(out, pos)
            val recLen = u16(out, pos + 2)
            val body = pos + 4
            if (body + recLen > out.size) break
            val recEnd = body + recLen
            val decryptStart = when {
                pos <= filePassOffset -> recEnd
                recId == 0x0809 || recId == 0x002F || recId == 0x00E1 -> recEnd
                recId == 0x0085 -> minOf(body + 4, recEnd)
                else -> body
            }
            seekAndConsume(recEnd, decryptStart, recEnd)
            pos = recEnd
        }
        neutralizeFilePassRecord(out, filePassOffset)
        return out
    }

    private fun neutralizeFilePassRecord(wb: ByteArray, filePassOffset: Int) {
        wb[filePassOffset] = 0xE2.toByte()
        wb[filePassOffset + 1] = 0x00
    }

    fun isEncryptedPpt(pptStream: ByteArray, currentUser: ByteArray?): Boolean {
        if (currentUser != null && currentUser.size >= 16) {
            if (i32(currentUser, 12) == 0xF3D1C4DF.toInt()) return true
        }
        val r = PptBytes(pptStream)
        val editOff = if (currentUser != null && currentUser.size >= 20) i32(currentUser, 16) else -1
        if (editOff >= 0) {
            val ue = r.record(editOff, pptStream.size)
            if (ue != null && ue.type == 0x0FF5 && ue.length >= 32 && r.s32(ue.start + 28) != 0) return true
        }
        return false
    }

    fun decryptPpt(
        pptStream: ByteArray,
        currentUser: ByteArray?,
        picturesStream: ByteArray?,
        password: String
    ): DecryptedPptStreams? {
        if (!isEncryptedPpt(pptStream, currentUser)) {
            return DecryptedPptStreams(pptStream, currentUser, picturesStream)
        }
        val r = PptBytes(pptStream)
        var editOffset = if (currentUser != null && currentUser.size >= 20) i32(currentUser, 16) else -1
        if (editOffset < 0 || r.record(editOffset, pptStream.size)?.type != 0x0FF5) {
            var p = 0
            while (p + 8 <= pptStream.size) {
                val rec = r.record(p, pptStream.size) ?: break
                if (rec.type == 0x0FF5) editOffset = p
                if (rec.end <= p) break
                p = rec.end
            }
        }
        if (editOffset < 0) return null

        val persist = HashMap<Int, Int>()
        val unencryptedOffsets = HashSet<Int>()
        var encryptSessionId = 0
        val visited = HashSet<Int>()
        var off = editOffset
        var first = true
        while (off >= 0 && visited.add(off) && visited.size <= 4096) {
            val ue = r.record(off, pptStream.size) ?: break
            if (ue.type != 0x0FF5) break
            unencryptedOffsets.add(ue.offset)
            if (first && ue.length >= 32) {
                encryptSessionId = r.s32(ue.start + 28)
                first = false
            }
            val dirOff = r.s32(ue.start + 12)
            r.record(dirOff, pptStream.size)?.takeIf { it.type == 0x1772 }?.let { dir ->
                unencryptedOffsets.add(dir.offset)
                var p = dir.start
                while (p + 4 <= dir.end) {
                    val h = r.s32(p)
                    val id = h and 0xFFFFF
                    val count = (h ushr 20) and 0xFFF
                    p += 4
                    for (i in 0 until count) {
                        if (p + 4 > dir.end) break
                        persist.putIfAbsent(id + i, r.s32(p))
                        p += 4
                    }
                }
            }
            val prev = r.s32(ue.start + 8)
            if (prev <= 0) break
            off = prev
        }

        if (encryptSessionId == 0) return null
        val cryptOffset = persist[encryptSessionId] ?: return null
        val cryptRec = r.record(cryptOffset, pptStream.size) ?: return null
        if (cryptRec.type != 0x2F14) return null
        unencryptedOffsets.add(cryptRec.offset)
        val encHeader = pptStream.copyOfRange(cryptRec.start, cryptRec.end)
        val vMajor = u16(encHeader, 0)
        val vMinor = u16(encHeader, 2)
        val keyGen = when {
            vMajor == 1 && vMinor == 1 -> parseBinaryRc4KeyGen(encHeader, password)
            vMajor in 2..4 && vMinor == 2 -> parseCryptoApiRc4KeyGen(encHeader, password)
            else -> null
        } ?: return null

        val outPpt = pptStream.copyOf()
        val sortedOffsets = persist.entries.sortedBy { it.value }
        for (idx in sortedOffsets.indices) {
            val (pid, recOff) = sortedOffsets[idx]
            if (pid == encryptSessionId || recOff in unencryptedOffsets) continue
            if (recOff < 0 || recOff + 8 > outPpt.size) continue
            val nextOff = if (idx + 1 < sortedOffsets.size) sortedOffsets[idx + 1].value else outPpt.size
            val rc4 = Rc4Cipher(keyGen(pid))
            val hdr = outPpt.copyOfRange(recOff, recOff + 8)
            rc4.processInPlace(hdr, 0, 8)
            val recLen = i32(hdr, 4).toLong() and 0xFFFFFFFFL
            val endOff = minOf(recOff.toLong() + 8L + recLen, nextOff.toLong(), outPpt.size.toLong()).toInt()
            if (endOff <= recOff + 8) {
                System.arraycopy(hdr, 0, outPpt, recOff, 8)
                continue
            }
            System.arraycopy(hdr, 0, outPpt, recOff, 8)
            rc4.processInPlace(outPpt, recOff + 8, endOff - (recOff + 8))
        }

        val firstUe = r.record(editOffset, outPpt.size)
        if (firstUe != null && firstUe.length >= 32) {
            outPpt[firstUe.start + 28] = 0
            outPpt[firstUe.start + 29] = 0
            outPpt[firstUe.start + 30] = 0
            outPpt[firstUe.start + 31] = 0
        }

        val outCu = currentUser?.copyOf()
        if (outCu != null && outCu.size >= 16 && i32(outCu, 12) == 0xF3D1C4DF.toInt()) {
            val plainToken = 0xE391C05F.toInt()
            outCu[12] = (plainToken and 0xFF).toByte()
            outCu[13] = ((plainToken ushr 8) and 0xFF).toByte()
            outCu[14] = ((plainToken ushr 16) and 0xFF).toByte()
            outCu[15] = ((plainToken ushr 24) and 0xFF).toByte()
        }

        val outPic = picturesStream?.let { pic ->
            val decPic = pic.copyOf()
            var p = 0
            while (p + 8 <= decPic.size) {
                val rc4 = Rc4Cipher(keyGen(0))
                rc4.processInPlace(decPic, p, 8)
                val len = i32(decPic, p + 4).toLong() and 0xFFFFFFFFL
                val end = minOf(p.toLong() + 8L + len, decPic.size.toLong()).toInt()
                if (end > p + 8) rc4.processInPlace(decPic, p + 8, end - (p + 8))
                if (end <= p) break
                p = end
            }
            decPic
        }

        return DecryptedPptStreams(outPpt, outCu, outPic)
    }

    internal fun pbkdf2HmacSha1(keyBytes: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hLen = 20
        val blocks = (keyLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val saltPlusIdx = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, saltPlusIdx, 0, salt.size)
        for (b in 1..blocks) {
            saltPlusIdx[salt.size] = ((b ushr 24) and 0xFF).toByte()
            saltPlusIdx[salt.size + 1] = ((b ushr 16) and 0xFF).toByte()
            saltPlusIdx[salt.size + 2] = ((b ushr 8) and 0xFF).toByte()
            saltPlusIdx[salt.size + 3] = (b and 0xFF).toByte()
            var u = mac.doFinal(saltPlusIdx)
            val t = u.copyOf()
            for (iter in 2..iterations) {
                u = mac.doFinal(u)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (b - 1) * hLen, hLen)
        }
        return out.copyOf(keyLen)
    }

    private class OdfEncEntry(
        val fullPath: String,
        val originalSize: Int,
        val checksumType: String,
        val checksum: ByteArray,
        val algorithmName: String,
        val iv: ByteArray,
        val salt: ByteArray,
        val iterations: Int,
        val keySize: Int,
        val startKeyAlg: String
    )

    fun isEncryptedOdfManifest(manifestBytes: ByteArray?): Boolean {
        if (manifestBytes == null || manifestBytes.isEmpty()) return false
        val txt = String(manifestBytes, StandardCharsets.UTF_8)
        return txt.contains("encryption-data", ignoreCase = true) ||
                txt.contains("encrypted-key", ignoreCase = true)
    }

    fun decryptOdfEntries(entries: Map<String, ByteArray>, password: String): Map<String, ByteArray>? {
        val manifestKey = entries.keys.firstOrNull { it.equals("META-INF/manifest.xml", ignoreCase = true) }
            ?: return null
        val manifestBytes = entries[manifestKey] ?: return null
        if (!isEncryptedOdfManifest(manifestBytes)) return entries
        val manifestText = String(manifestBytes, StandardCharsets.UTF_8)
        if (manifestText.contains("encrypted-key", ignoreCase = true)) {
            throw SecurityException("UNSUPPORTED_ENCRYPTION: OpenPGP-encrypted ODF document is not supported.")
        }

        val encEntries = ArrayList<OdfEncEntry>()
        val doc = parseSafeXml(manifestBytes)
            ?: throw SecurityException("UNSUPPORTED_ENCRYPTION: ODF encryption manifest could not be parsed.")
        val fileEntries = doc.getElementsByTagName("*")
        for (idx in 0 until fileEntries.length) {
            val fe = fileEntries.item(idx) as? Element ?: continue
            val feLocal = (fe.localName ?: fe.nodeName?.substringAfterLast(':') ?: "").lowercase(Locale.ROOT)
            if (feLocal != "file-entry") continue
            val fullPath = elementAttr(fe, "full-path") ?: continue
            val origSize = elementAttr(fe, "size")?.toIntOrNull() ?: -1
            val children = fe.getElementsByTagName("*")
            var encEl: Element? = null
            var algEl: Element? = null
            var kdEl: Element? = null
            var skEl: Element? = null
            for (cIdx in 0 until children.length) {
                val cel = children.item(cIdx) as? Element ?: continue
                when ((cel.localName ?: cel.nodeName?.substringAfterLast(':') ?: "").lowercase(Locale.ROOT)) {
                    "encryption-data" -> encEl = cel
                    "algorithm" -> algEl = cel
                    "key-derivation" -> kdEl = cel
                    "start-key-generation" -> skEl = cel
                }
            }
            if (encEl == null) continue
            val algName = algEl?.let { elementAttr(it, "algorithm-name") } ?: ""
            val kdName = kdEl?.let { elementAttr(it, "key-derivation-name") } ?: ""
            if (kdName.contains("argon2", ignoreCase = true) ||
                algName.contains("gcm", ignoreCase = true) ||
                (!algName.contains("blowfish", ignoreCase = true) && !algName.contains("aes", ignoreCase = true))
            ) {
                throw SecurityException("UNSUPPORTED_ENCRYPTION: ODF document uses an encryption algorithm (such as AES-256-GCM or Argon2id) that is not supported in this version.")
            }
            if (algEl == null || kdEl == null) {
                throw SecurityException("UNSUPPORTED_ENCRYPTION: ODF document uses an unsupported encryption manifest structure.")
            }
            val csType = elementAttr(encEl, "checksum-type") ?: ""
            val csBytes = decodeBase64(elementAttr(encEl, "checksum"))
                ?: throw SecurityException("UNSUPPORTED_ENCRYPTION: ODF document is missing a valid encryption checksum.")
            val ivBytes = decodeBase64(elementAttr(algEl, "initialisation-vector"))
                ?: throw SecurityException("UNSUPPORTED_ENCRYPTION: ODF document is missing a valid initialisation vector.")
            val saltBytes = decodeBase64(elementAttr(kdEl, "salt"))
                ?: throw SecurityException("UNSUPPORTED_ENCRYPTION: ODF document is missing a valid key derivation salt.")
            val iters = elementAttr(kdEl, "iteration-count")?.toIntOrNull() ?: 1024
            val keySize = elementAttr(kdEl, "key-size")?.toIntOrNull()
                ?: if (algName.contains("aes256", true)) 32 else if (algName.contains("aes192", true)) 24 else 16
            val startKeyName = skEl?.let { elementAttr(it, "start-key-generation-name") } ?: ""
            val startKeyAlg = if (startKeyName.contains("sha256", true)) "SHA-256" else "SHA-1"
            encEntries.add(OdfEncEntry(fullPath, origSize, csType, csBytes, algName, ivBytes, saltBytes, iters, keySize, startKeyAlg))
        }

        if (encEntries.isEmpty()) {
            throw SecurityException("UNSUPPORTED_ENCRYPTION: ODF document is encrypted with an unsupported encryption scheme.")
        }
        val out = LinkedHashMap<String, ByteArray>(entries)
        val pwdUtf8 = password.toByteArray(StandardCharsets.UTF_8)
        val startKeyCache = HashMap<String, ByteArray>()

        for (meta in encEntries) {
            val matchKey = entries.keys.firstOrNull { it.equals(meta.fullPath, ignoreCase = true) } ?: continue
            val cipherData = entries[matchKey] ?: continue
            if (meta.iterations < 1 || meta.iterations > 2_000_000) return null
            val startKey = startKeyCache.getOrPut(meta.startKeyAlg) { digest(meta.startKeyAlg, pwdUtf8) }
            val key = pbkdf2HmacSha1(startKey, meta.salt, meta.iterations, meta.keySize)

            val plainDeflated = try {
                if (meta.algorithmName.contains("blowfish", ignoreCase = true)) {
                    val cipher = Cipher.getInstance("Blowfish/CFB/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(meta.iv))
                    cipher.doFinal(cipherData)
                } else {
                    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(meta.iv))
                    val raw = cipher.doFinal(cipherData)
                    if (raw.isEmpty()) return null
                    val padLen = raw[raw.size - 1].toInt() and 0xFF
                    if (padLen !in 1..16 || padLen > raw.size) return null
                    raw.copyOf(raw.size - padLen)
                }
            } catch (_: Throwable) {
                return null
            }

            val csAlg = if (meta.checksumType.contains("256", ignoreCase = true)) "SHA-256" else "SHA-1"
            val csLen = if (meta.checksumType.contains("1k", ignoreCase = true)) minOf(1024, plainDeflated.size) else plainDeflated.size
            val calcCs = digest(csAlg, plainDeflated.copyOf(csLen))
            if (!calcCs.contentEquals(meta.checksum)) return null

            val inflated = rawInflate(plainDeflated)
            out[matchKey] = inflated ?: plainDeflated
        }

        val cleanedManifest = String(manifestBytes, StandardCharsets.UTF_8)
            .replace(Regex("<[a-zA-Z0-9_-]*:?encryption-data[^>]*>.*?</[a-zA-Z0-9_-]*:?encryption-data>", RegexOption.DOT_MATCHES_ALL), "")
        out[manifestKey] = cleanedManifest.toByteArray(StandardCharsets.UTF_8)
        return out
    }

    private fun rawInflate(data: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val bos = ByteArrayOutputStream(maxOf(data.size * 2, 256))
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n > 0) {
                    bos.write(buf, 0, n)
                    if (bos.size() > 128 * 1024 * 1024) return null
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            if (bos.size() > 0) bos.toByteArray() else null
        } catch (_: Throwable) {
            null
        } finally {
            inflater.end()
        }
    }
}
