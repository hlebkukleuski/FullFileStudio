/*
 * FullFileStudio
 * Copyright (C) 2026 Hleb Kukleuski (https://github.com/hlebkukleuski)
 *
 * Dual-licensed under GPL-3.0-or-later and Commercial License.
 * See LICENSING.md and LICENSE in the repository root for details.
 */

package me.hlebkukleuski.fullfilestudio.core.media

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioPcmQualityProcessorTest {

    @Test
    fun resolvesOutputFormatAccordingToModeAndDeviceCapabilities() {
        val studioOnUsbDac = AudioQualitySpec("96_24", 96_000, 24, maxDeviceSampleRateHz = 192_000)
        assertEquals(
            96_000 to AudioFormat.ENCODING_PCM_FLOAT,
            AudioPcmQualityProcessor.resolveOutputFormat(44_100, AudioFormat.ENCODING_PCM_16BIT, studioOnUsbDac)
        )

        val studioOn48kSpeaker = AudioQualitySpec("96_24", 96_000, 24, maxDeviceSampleRateHz = 48_000)
        assertEquals(
            48_000 to AudioFormat.ENCODING_PCM_FLOAT,
            AudioPcmQualityProcessor.resolveOutputFormat(44_100, AudioFormat.ENCODING_PCM_16BIT, studioOn48kSpeaker)
        )

        val standard44 = AudioQualitySpec("44_16", 44_100, 16, maxDeviceSampleRateHz = 96_000)
        assertEquals(
            44_100 to AudioFormat.ENCODING_PCM_16BIT,
            AudioPcmQualityProcessor.resolveOutputFormat(48_000, AudioFormat.ENCODING_PCM_FLOAT, standard44)
        )

        val autoHiRes = AudioQualitySpec("auto", 0, 0, maxDeviceSampleRateHz = 192_000)
        assertEquals(
            88_200 to AudioFormat.ENCODING_PCM_FLOAT,
            AudioPcmQualityProcessor.resolveOutputFormat(88_200, AudioFormat.ENCODING_PCM_24BIT_PACKED, autoHiRes)
        )
    }

    @Test
    fun streamingUpsampler44k1To96kFloatPreservesSineFrequencyAmplitudeAndPhaseContinuity() {
        val inRate = 44_100
        val outRate = 96_000
        val freqHz = 1_000.0
        val totalInFrames = 4_410
        val chunkFrames = 441
        val resampler = StreamingPcmResampler()

        val outLeft = ArrayList<Float>(10_000)
        val outRight = ArrayList<Float>(10_000)

        for (chunkStart in 0 until totalInFrames step chunkFrames) {
            val inBuf = ByteBuffer.allocate(chunkFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until chunkFrames) {
                val frame = chunkStart + i
                val t = frame.toDouble() / inRate.toDouble()
                val l = (0.8 * sin(2.0 * PI * freqHz * t) * 32767.0).toInt().toShort()
                val r = (0.5 * cos(2.0 * PI * freqHz * t) * 32767.0).toInt().toShort()
                inBuf.putShort(l)
                inBuf.putShort(r)
            }
            inBuf.flip()

            val outBuf = resampler.process(
                input = inBuf,
                inSampleRateHz = inRate,
                channels = 2,
                inEncoding = AudioFormat.ENCODING_PCM_16BIT,
                outSampleRateHz = outRate,
                outEncoding = AudioFormat.ENCODING_PCM_FLOAT
            ).order(ByteOrder.LITTLE_ENDIAN)

            while (outBuf.remaining() >= 8) {
                outLeft.add(outBuf.float)
                outRight.add(outBuf.float)
            }
        }

        assertTrue("Expected ~9600 resampled frames for 0.1s at 96 kHz, got ${outLeft.size}", outLeft.size in 9595..9605)

        var maxStepJump = 0f
        var peakLeft = 0f
        var risingZeroCrossings = 0
        for (i in 10 until outLeft.size - 10) {
            val cur = outLeft[i]
            val prev = outLeft[i - 1]
            peakLeft = maxOf(peakLeft, abs(cur))
            maxStepJump = maxOf(maxStepJump, abs(cur - prev))
            if (prev <= 0f && cur > 0f) {
                risingZeroCrossings++
            }
        }

        assertTrue("Expected peak amplitude near 0.80, got $peakLeft", peakLeft in 0.78f..0.82f)
        assertTrue("Expected smooth Catmull-Rom continuity without clicks, maxJump=$maxStepJump", maxStepJump < 0.09f)
        assertTrue("Expected ~100 cycles of 1 kHz in 0.1s, got $risingZeroCrossings", risingZeroCrossings in 98..102)
    }

    @Test
    fun preservesSub16BitDynamicRangeOn24BitPackedInputToFloatOutput() {
        val resampler = StreamingPcmResampler()
        val s24QuietSignal = 64
        val packed = ByteBuffer.allocate(6)
        packed.put((s24QuietSignal and 0xFF).toByte())
        packed.put(((s24QuietSignal ushr 8) and 0xFF).toByte())
        packed.put(((s24QuietSignal ushr 16) and 0xFF).toByte())
        val neg = -s24QuietSignal
        packed.put((neg and 0xFF).toByte())
        packed.put(((neg ushr 8) and 0xFF).toByte())
        packed.put(((neg ushr 16) and 0xFF).toByte())
        packed.flip()

        val outFloat = resampler.process(
            input = packed,
            inSampleRateHz = 96_000,
            channels = 1,
            inEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED,
            outSampleRateHz = 96_000,
            outEncoding = AudioFormat.ENCODING_PCM_FLOAT
        ).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(8, outFloat.remaining())
        val f0 = outFloat.float
        val f1 = outFloat.float
        val expected = 64f / 8_388_608f
        assertEquals(expected, f0, 1e-9f)
        assertEquals(-expected, f1, 1e-9f)
    }
}
