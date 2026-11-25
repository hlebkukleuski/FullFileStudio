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

import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import me.hlebkukleuski.fullfilestudio.core.database.DuckDbReader
import me.hlebkukleuski.fullfilestudio.core.database.ParquetReader
import me.hlebkukleuski.fullfilestudio.core.database.SqliteReader
import me.hlebkukleuski.fullfilestudio.core.database.ThriftCompactReader
import me.hlebkukleuski.fullfilestudio.core.media.AudioPcmQualityProcessor
import me.hlebkukleuski.fullfilestudio.core.media.AudioQualitySpec
import me.hlebkukleuski.fullfilestudio.core.media.StreamingPcmResampler
import me.hlebkukleuski.fullfilestudio.core.security.CcittFaxDecoder
import me.hlebkukleuski.fullfilestudio.core.security.Lz4RawDecoder
import me.hlebkukleuski.fullfilestudio.core.security.SnappyRawDecoder
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource
import me.hlebkukleuski.fullfilestudio.core.security.ZstdDecoder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic property and mutation-fuzzing test suite across binary format parsers,
 * decompression codecs, database engines, cryptographic containers, and media DSP.
 * Every test enforces a strict wall-clock timeout and asserts zero OutOfMemoryError or hangs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BinaryParserFuzzSafetyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test(timeout = 15_000L)
    fun woff2DecoderRejectsTruncatedBitFlippedAndOversizedHeaders() {
        val rng = Random(0x5EEDC001)
        assertTrue(Woff2Decoder.decodeToSfnt(ByteArray(0)) == null)

        val oversizedHeader = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x774F4632)
            putInt(0x00010000)
            putInt(48)
            putShort(12)
            putShort(0)
            putInt(Int.MAX_VALUE)
            putInt(Int.MAX_VALUE)
        }.array()
        assertTrue(Woff2Decoder.decodeToSfnt(oversizedHeader) == null)

        repeat(250) { iteration ->
            val len = rng.nextInt(1, 384)
            val buf = rng.nextBytes(len)
            if (iteration % 2 == 0 && len >= 4) {
                buf[0] = 0x77
                buf[1] = 0x4F
                buf[2] = 0x46
                buf[3] = 0x32
            }
            try {
                val decoded = Woff2Decoder.decodeToSfnt(buf)
                if (decoded != null) {
                    assertTrue(decoded.size <= 96 * 1024 * 1024)
                }
            } catch (_: RuntimeException) {
                /* Expected clean rejection on malformed binary input */
            }
        }
    }

    @Test(timeout = 15_000L)
    fun compressionCodecsRejectCorruptedSnappyLz4ZstdAndCcittPayloads() {
        val rng = Random(0x5EEDC002)

        val zstdBombHeader = byteArrayOf(
            0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(),
            0xC0.toByte(),
            0x00, 0x00, 0x00, 0x7F, 0x00, 0x00, 0x00, 0x00
        )
        var zstdRejected = false
        try {
            ZstdDecoder.decompress(zstdBombHeader, maxOutput = 65_536)
        } catch (_: IllegalArgumentException) {
            zstdRejected = true
        }
        assertTrue("Expected Zstd oversized frame header to be rejected", zstdRejected)

        repeat(300) { idx ->
            val payload = rng.nextBytes(rng.nextInt(0, 256))
            try {
                SnappyRawDecoder.decompress(payload, maxOutput = 65_536)
            } catch (_: Exception) {}

            try {
                Lz4RawDecoder.decompress(payload, 0, payload.size, rng.nextInt(0, 8192))
            } catch (_: Exception) {}

            val zstdCandidate = if (idx % 3 == 0 && payload.size >= 4) {
                payload.copyOf().also {
                    it[0] = 0x28
                    it[1] = 0xB5.toByte()
                    it[2] = 0x2F
                    it[3] = 0xFD.toByte()
                }
            } else {
                payload
            }
            try {
                ZstdDecoder.decompress(zstdCandidate, maxOutput = 65_536)
            } catch (_: Exception) {}

            if (idx < 60 && payload.isNotEmpty()) {
                try {
                    CcittFaxDecoder.decode(
                        data = payload,
                        offset = 0,
                        length = payload.size,
                        width = rng.nextInt(1, 128),
                        rows = rng.nextInt(1, 64),
                        compression = if (idx % 2 == 0) 4 else 3,
                        t4Options = idx and 3,
                        lsbFirst = idx % 5 == 0
                    )
                } catch (_: Exception) {}
            }
        }
    }

    @Test(timeout = 15_000L)
    fun duckDbSqliteAndParquetReadersSurviveAdversarialHeadersAndFooters() {
        val rng = Random(0x5EEDC003)

        repeat(150) { idx ->
            val raw = rng.nextBytes(rng.nextInt(16, 8192))

            if (idx % 3 == 0 && raw.size >= 16) {
                "DUCK".toByteArray(StandardCharsets.US_ASCII).copyInto(raw, destinationOffset = 8)
            }
            try {
                DuckDbReader.isDuckDbHeader(raw)
                DuckDbReader.isEncrypted(raw)
                DuckDbReader.parse(raw)
            } catch (_: Exception) {}

            val sqliteCandidate = raw.copyOf()
            if (sqliteCandidate.size >= 100) {
                "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(sqliteCandidate, 0)
            }
            try {
                val reader = SqliteReader(
                    source = { offset, dst, length ->
                        if (offset < 0L || offset >= sqliteCandidate.size) {
                            0
                        } else {
                            val n = minOf(length, (sqliteCandidate.size - offset).toInt())
                            System.arraycopy(sqliteCandidate, offset.toInt(), dst, 0, n)
                            n
                        }
                    },
                    fileSize = sqliteCandidate.size.toLong()
                )
                reader.findEntry("users")
            } catch (_: Exception) {}

            try {
                ThriftCompactReader(raw).readStruct()
            } catch (_: Exception) {}

            val parquetCandidate = raw.copyOf()
            if (parquetCandidate.size >= 12) {
                "PAR1".toByteArray(StandardCharsets.US_ASCII).copyInto(parquetCandidate, 0)
                "PAR1".toByteArray(StandardCharsets.US_ASCII).copyInto(parquetCandidate, parquetCandidate.size - 4)
            }
            try {
                val pr = ParquetReader(
                    source = { offset, length ->
                        if (offset < 0L || length < 0 || offset + length > parquetCandidate.size) {
                            ByteArray(0)
                        } else {
                            parquetCandidate.copyOfRange(offset.toInt(), (offset + length).toInt())
                        }
                    },
                    fileSize = parquetCandidate.size.toLong()
                )
                if (pr.leaves.isNotEmpty() && pr.numRows > 0) {
                    pr.cell(0L, pr.leaves.first())
                }
            } catch (_: Exception) {}
        }
    }

    @Test(timeout = 15_000L)
    fun officeCryptoAndPdfDrmParsersSurviveMalformedContainers() {
        val rng = Random(0x5EEDC004)
        val oleMagic = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )

        val xxeManifest = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE manifest [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
                <manifest:file-entry manifest:full-path="content.xml">
                    <manifest:encryption-data manifest:checksum="&xxe;"/>
                </manifest:file-entry>
            </manifest:manifest>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        try {
            OfficeCrypto.isEncryptedOdfManifest(xxeManifest)
            OfficeCrypto.decryptOdfEntries(
                mapOf(
                    "META-INF/manifest.xml" to xxeManifest,
                    "content.xml" to rng.nextBytes(64)
                ),
                "secret"
            )
        } catch (_: Exception) {}

        repeat(120) { idx ->
            val buf = rng.nextBytes(rng.nextInt(8, 2048))
            if (idx % 2 == 0 && buf.size >= 8) {
                oleMagic.copyInto(buf, 0)
            }
            try {
                OfficeCrypto.isOfficeIrmBytes(buf)
                OfficeCrypto.decryptOoxmlBytes(buf, "test1234")
                OfficeCrypto.isEncryptedDoc(buf)
                OfficeCrypto.isEncryptedXls(buf)
                OfficeCrypto.decryptXlsWithDefaultFallback(buf, "pass")
                OfficeCrypto.isEncryptedPpt(buf, null)
            } catch (_: Exception) {}

            val pdfCandidate = if (idx % 2 == 0) {
                ("%PDF-1.7\n1 0 obj << /Filter /#41dobe.PubSec /V 5 >> endobj\ntrailer << /Encrypt 1 0 R >>\n").toByteArray(StandardCharsets.ISO_8859_1) + buf
            } else {
                buf
            }
            try {
                PdfPermissionParser.isDrmProtected(pdfCandidate)
            } catch (_: Exception) {}
        }
    }

    @Test(timeout = 15_000L)
    fun tiffDecoderAndAudioPcmResamplerHandleExtremeDimensionsAndFormats() {
        val rng = Random(0x5EEDC005)

        val cyclicTiff = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x49)
            put(0x49)
            putShort(42)
            putInt(8)
            putShort(2)
            putShort(256); putShort(4); putInt(1); putInt(1_000_000)
            putShort(257); putShort(4); putInt(1); putInt(1_000_000)
            putInt(8)
        }.array()

        val tiffFile: File = tempFolder.newFile("cyclic.tiff")
        tiffFile.writeBytes(cyclicTiff)
        ParcelFileDescriptor.open(tiffFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            ZeroCacheReadableSource(pfd.fileDescriptor).use { src ->
                assertTrue(TiffDecoder.isTiffHeader(cyclicTiff, cyclicTiff.size))
                val pages = TiffDecoder.getPageCount(src)
                assertTrue(pages >= 1)
                try {
                    TiffDecoder.decodePage(src, 0)
                } catch (_: Exception) {}
            }
        }

        val resampler = StreamingPcmResampler()
        val rates = intArrayOf(8_000, 22_050, 44_100, 48_000, 88_200, 96_000, 192_000)
        val encodings = intArrayOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        repeat(80) {
            val inRate = rates[rng.nextInt(rates.size)]
            val outRate = rates[rng.nextInt(rates.size)]
            val inEnc = encodings[rng.nextInt(encodings.size)]
            val (resolvedRate, resolvedEnc) = AudioPcmQualityProcessor.resolveOutputFormat(
                inSampleRateHz = inRate,
                inEncoding = inEnc,
                spec = AudioQualitySpec(
                    modeId = "96_24",
                    targetSampleRateHz = outRate,
                    targetBitDepth = if (rng.nextBoolean()) 24 else 16,
                    maxDeviceSampleRateHz = 192_000
                )
            )
            val pcmBytes = rng.nextBytes(rng.nextInt(0, 1024))
            val outBuf = resampler.process(
                input = ByteBuffer.wrap(pcmBytes),
                inSampleRateHz = inRate,
                channels = rng.nextInt(1, 6),
                inEncoding = inEnc,
                outSampleRateHz = resolvedRate,
                outEncoding = resolvedEnc
            )
            assertNotNull(outBuf)
        }
    }
}
