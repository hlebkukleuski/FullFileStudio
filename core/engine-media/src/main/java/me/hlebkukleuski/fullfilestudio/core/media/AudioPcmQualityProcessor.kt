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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class AudioQualitySpec(
    val modeId: String = "auto",
    val targetSampleRateHz: Int = 0,
    val targetBitDepth: Int = 0,
    val maxDeviceSampleRateHz: Int = 192_000
)

object AudioPcmQualityProcessor {

    fun resolveOutputFormat(
        inSampleRateHz: Int,
        inEncoding: Int,
        spec: AudioQualitySpec
    ): Pair<Int, Int> {
        val safeInRate = inSampleRateHz.coerceIn(8_000, 384_000)
        val maxRate = spec.maxDeviceSampleRateHz.coerceIn(8_000, 384_000)
        val outRate = if (spec.targetSampleRateHz > 0) {
            min(spec.targetSampleRateHz, maxRate).coerceAtLeast(8_000)
        } else {
            min(safeInRate, maxRate)
        }
        val outEnc = when {
            spec.targetBitDepth >= 24 -> AudioFormat.ENCODING_PCM_FLOAT
            spec.targetBitDepth in 1..16 -> AudioFormat.ENCODING_PCM_16BIT
            inEncoding == AudioFormat.ENCODING_PCM_FLOAT ||
                inEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED ||
                inEncoding == AudioFormat.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        return outRate to outEnc
    }

    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> 2
    }
}

class StreamingPcmResampler {

    private var configuredChannels = 0
    private var configuredInRate = 0
    private var configuredOutRate = 0
    private var phase = 0.0
    private var historyInitialized = false
    private var tailFrames = FloatArray(0)
    private var lowPassState = FloatArray(0)
    private var workScratch = FloatArray(0)
    private var resampledScratch = FloatArray(0)
    private var outputByteScratch = ByteArray(0)

    fun reset() {
        phase = 0.0
        historyInitialized = false
    }

    fun process(
        input: ByteBuffer,
        inSampleRateHz: Int,
        channels: Int,
        inEncoding: Int,
        outSampleRateHz: Int,
        outEncoding: Int
    ): ByteBuffer {
        val ch = channels.coerceIn(1, 8)
        val inBps = AudioPcmQualityProcessor.bytesPerSample(inEncoding)
        val inFrameBytes = ch * inBps
        val totalBytes = input.remaining()
        val inFrames = if (inFrameBytes > 0) totalBytes / inFrameBytes else 0
        if (inFrames <= 0) return ByteBuffer.allocate(0)

        if (ch != configuredChannels || inSampleRateHz != configuredInRate || outSampleRateHz != configuredOutRate) {
            configuredChannels = ch
            configuredInRate = inSampleRateHz
            configuredOutRate = outSampleRateHz
            phase = 0.0
            historyInitialized = false
            tailFrames = FloatArray(3 * ch)
            lowPassState = FloatArray(ch)
        }

        val totalWorkFrames = 3 + inFrames
        val neededWorkFloats = totalWorkFrames * ch
        if (workScratch.size < neededWorkFloats) {
            workScratch = FloatArray(max(neededWorkFloats, workScratch.size * 2))
        }
        decodeToFloatInterleaved(input, inFrames, ch, inEncoding, workScratch, dstOffsetFloats = 3 * ch)

        val outFrames: Int
        val outFloats: FloatArray
        var outStartFloats = 0
        if (inSampleRateHz == outSampleRateHz) {
            outFrames = inFrames
            outFloats = workScratch
            outStartFloats = 3 * ch
        } else {
            val step = inSampleRateHz.toDouble() / outSampleRateHz.toDouble()
            if (step > 1.0001) {
                val cutoffRatio = (0.45 / step).coerceIn(0.05, 0.49)
                val alpha = ((2.0 * Math.PI * cutoffRatio) / (2.0 * Math.PI * cutoffRatio + 1.0)).toFloat()
                for (f in 0 until inFrames) {
                    val base = (3 + f) * ch
                    for (c in 0 until ch) {
                        val prev = if (!historyInitialized && f == 0) workScratch[base + c] else lowPassState[c]
                        val filtered = prev + alpha * (workScratch[base + c] - prev)
                        lowPassState[c] = filtered
                        workScratch[base + c] = filtered
                    }
                }
            }

            if (!historyInitialized) {
                for (c in 0 until ch) {
                    val firstSample = workScratch[3 * ch + c]
                    tailFrames[c] = firstSample
                    tailFrames[ch + c] = firstSample
                    tailFrames[2 * ch + c] = firstSample
                }
                historyInitialized = true
            }
            System.arraycopy(tailFrames, 0, workScratch, 0, 3 * ch)

            val maxOutFrames = ((inFrames.toDouble() + 4.0) / step).toInt() + 8
            val neededOutFloats = maxOutFrames * ch
            if (resampledScratch.size < neededOutFloats) {
                resampledScratch = FloatArray(max(neededOutFloats, resampledScratch.size * 2))
            }

            var produced = 0
            var pos = phase
            while (pos < inFrames.toDouble()) {
                val idx = floor(pos).toInt()
                if (idx >= inFrames) break
                val frac = (pos - idx.toDouble()).toFloat()
                val i0 = idx * ch
                val i1 = (idx + 1) * ch
                val i2 = (idx + 2) * ch
                val i3 = (idx + 3) * ch
                val outBase = produced * ch
                for (c in 0 until ch) {
                    resampledScratch[outBase + c] = catmullRom(
                        workScratch[i0 + c],
                        workScratch[i1 + c],
                        workScratch[i2 + c],
                        workScratch[i3 + c],
                        frac
                    ).coerceIn(-1f, 1f)
                }
                produced++
                pos += step
            }
            phase = pos - inFrames.toDouble()
            System.arraycopy(workScratch, inFrames * ch, tailFrames, 0, 3 * ch)

            outFrames = produced
            outFloats = resampledScratch
        }

        val outBps = if (outEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val outByteLen = outFrames * ch * outBps
        if (outputByteScratch.size < outByteLen) {
            outputByteScratch = ByteArray(max(outByteLen, outputByteScratch.size * 2))
        }
        encodeFromFloatInterleaved(outFloats, outStartFloats, outFrames * ch, outEncoding, outputByteScratch)
        return ByteBuffer.wrap(outputByteScratch, 0, outByteLen)
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val a0 = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3
        val a1 = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3
        val a2 = -0.5f * p0 + 0.5f * p2
        return ((a0 * t + a1) * t + a2) * t + p1
    }

    private fun decodeToFloatInterleaved(
        src: ByteBuffer,
        frames: Int,
        channels: Int,
        encoding: Int,
        dst: FloatArray,
        dstOffsetFloats: Int
    ) {
        val totalSamples = frames * channels
        val dup = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in 0 until totalSamples) {
                    val u = dup.get().toInt() and 0xFF
                    dst[dstOffsetFloats + i] = ((u - 128) / 128f).coerceIn(-1f, 1f)
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                for (i in 0 until totalSamples) {
                    val b0 = dup.get().toInt() and 0xFF
                    val b1 = dup.get().toInt() and 0xFF
                    val b2 = dup.get().toInt()
                    val s24 = b0 or (b1 shl 8) or (b2 shl 16)
                    dst[dstOffsetFloats + i] = (s24 / 8388608f).coerceIn(-1f, 1f)
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                for (i in 0 until totalSamples) {
                    val s32 = dup.int
                    dst[dstOffsetFloats + i] = (s32.toDouble() / 2147483648.0).toFloat().coerceIn(-1f, 1f)
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                for (i in 0 until totalSamples) {
                    val f = dup.float
                    dst[dstOffsetFloats + i] = if (f.isFinite()) f.coerceIn(-1f, 1f) else 0f
                }
            }
            else -> {
                for (i in 0 until totalSamples) {
                    val s16 = dup.short.toInt()
                    dst[dstOffsetFloats + i] = (s16 / 32768f).coerceIn(-1f, 1f)
                }
            }
        }
    }

    private fun encodeFromFloatInterleaved(
        src: FloatArray,
        srcOffsetFloats: Int,
        sampleCount: Int,
        outEncoding: Int,
        dst: ByteArray
    ) {
        if (outEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            var pos = 0
            for (i in 0 until sampleCount) {
                val bits = src[srcOffsetFloats + i].coerceIn(-1f, 1f).toRawBits()
                dst[pos] = (bits and 0xFF).toByte()
                dst[pos + 1] = ((bits ushr 8) and 0xFF).toByte()
                dst[pos + 2] = ((bits ushr 16) and 0xFF).toByte()
                dst[pos + 3] = ((bits ushr 24) and 0xFF).toByte()
                pos += 4
            }
        } else {
            var pos = 0
            for (i in 0 until sampleCount) {
                val scaled = (src[srcOffsetFloats + i].coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
                dst[pos] = (scaled and 0xFF).toByte()
                dst[pos + 1] = ((scaled ushr 8) and 0xFF).toByte()
                pos += 2
            }
        }
    }
}
