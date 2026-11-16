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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import com.omnireader.core.engine.DocumentSession
import com.omnireader.core.engine.FileEngineProvider
import com.omnireader.core.engine.PackedTileKey
import com.omnireader.core.engine.ZeroCacheReadableSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min

internal class CleanRoomAviDemuxer(private val source: ZeroCacheReadableSource) {
    data class AviChunkEntry(
        val offset: Long,
        val size: Int,
        val isVideo: Boolean
    )

    var width: Int = 640
        private set
    var height: Int = 480
        private set
    var frameDurationMs: Long = 33L
        private set
    var totalDurationMs: Int = 0
        private set
    var videoFourCc: String = ""
        private set
    var videoMimeType: String? = null
        private set
    var videoCodecExtraData: ByteArray? = null
        private set

    var audioFormatTag: Int = 0
        private set
    var audioMimeType: String? = null
        private set
    var audioSampleRate: Int = 0
        private set
    var audioChannels: Int = 0
        private set
    var audioBitsPerSample: Int = 16
        private set
    var audioByteRate: Int = 0
        private set
    var audioCodecExtraData: ByteArray? = null
        private set

    val videoChunks = ArrayList<AviChunkEntry>()
    val audioChunks = ArrayList<AviChunkEntry>()

    val isValidAvi: Boolean

    init {
        isValidAvi = parseRiffAviStructure()
    }

    private fun mapVideoFourCcToMime(fourCc: String): String? {
        return when (fourCc.uppercase(Locale.ROOT).trim()) {
            "H264", "AVC1", "X264", "DAVC", "VSSH" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "FMP4", "DX50", "XVID", "DIVX", "MP4V", "DIV3", "DIV4", "DIV5", "3IV2" -> MediaFormat.MIMETYPE_VIDEO_MPEG4
            "HEVC", "H265", "X265", "HVC1" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "VP80" -> MediaFormat.MIMETYPE_VIDEO_VP8
            "VP90" -> MediaFormat.MIMETYPE_VIDEO_VP9
            "MPG1", "MPG2", "MPEG" -> MediaFormat.MIMETYPE_VIDEO_MPEG2
            "WMV3", "WVC1" -> "video/x-ms-wmv"
            "MJPG", "JPEG", "DMB1" -> "video/mjpeg"
            else -> null
        }
    }

    private fun mapAudioTagToMime(formatTag: Int): String? {
        return when (formatTag) {
            0x0001 -> MediaFormat.MIMETYPE_AUDIO_RAW
            0x0006 -> MediaFormat.MIMETYPE_AUDIO_G711_ALAW
            0x0007 -> MediaFormat.MIMETYPE_AUDIO_G711_MLAW
            0x0050, 0x0055 -> MediaFormat.MIMETYPE_AUDIO_MPEG
            0x00FF, 0x1600, 0x1601, 0x1610 -> MediaFormat.MIMETYPE_AUDIO_AAC
            0x2000 -> MediaFormat.MIMETYPE_AUDIO_AC3
            0x2001 -> MediaFormat.MIMETYPE_AUDIO_EAC3
            0x0161, 0x0162 -> "audio/x-ms-wma"
            else -> null
        }
    }

    private fun parseRiffAviStructure(): Boolean {
        val fileSize = source.size
        if (fileSize < 32L) return false
        val headBuf = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN)
        if (source.readAt(0L, headBuf) < 12) return false
        headBuf.flip()
        val headBytes = ByteArray(12)
        headBuf.get(headBytes)
        if (String(headBytes, 0, 4, StandardCharsets.US_ASCII) != "RIFF" ||
            String(headBytes, 8, 4, StandardCharsets.US_ASCII) != "AVI "
        ) {
            return false
        }

        walkRiffChunks(12L, minOf(fileSize, 512L * 1024L * 1024L), 0)
        if (videoChunks.isNotEmpty()) {
            totalDurationMs = (videoChunks.size * frameDurationMs).toInt().coerceAtLeast(1000)
        } else if (audioChunks.isNotEmpty()) {
            val totalAudioBytes = audioChunks.sumOf { it.size.toLong() }
            val bytesPerSec = if (audioByteRate > 0) {
                audioByteRate
            } else {
                (audioSampleRate.coerceAtLeast(8000) * audioChannels.coerceAtLeast(1) * (audioBitsPerSample / 8).coerceAtLeast(1)).coerceAtLeast(1)
            }
            totalDurationMs = ((totalAudioBytes * 1000L) / bytesPerSec).toInt().coerceAtLeast(1000)
        }
        return videoChunks.isNotEmpty() || audioChunks.isNotEmpty()
    }

    private fun walkRiffChunks(startOffset: Long, limitOffset: Long, depth: Int) {
        if (depth > 6) return
        val chunkHeader = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN)
        var pos = startOffset
        var currentStreamType = ""

        while (pos + 8L <= limitOffset && videoChunks.size < 100_000) {
            chunkHeader.clear()
            chunkHeader.limit(8)
            if (source.readAt(pos, chunkHeader) < 8) break
            val idBytes = ByteArray(4)
            chunkHeader.position(0)
            chunkHeader.get(idBytes)
            val fourCc = String(idBytes, StandardCharsets.US_ASCII)
            val chunkSize = chunkHeader.getInt(4).toLong() and 0xFFFFFFFFL
            val dataStart = pos + 8L
            val paddedSize = (chunkSize + 1L) and -2L
            val nextPos = dataStart + paddedSize

            if (fourCc == "LIST" || fourCc == "RIFF") {
                chunkHeader.clear()
                chunkHeader.limit(4)
                if (source.readAt(dataStart, chunkHeader) == 4) {
                    val listTypeBytes = ByteArray(4)
                    chunkHeader.position(0)
                    chunkHeader.get(listTypeBytes)
                    val listType = String(listTypeBytes, StandardCharsets.US_ASCII)
                    if (listType in setOf("hdrl", "strl", "movi", "rec ")) {
                        walkRiffChunks(dataStart + 4L, minOf(limitOffset, dataStart + chunkSize), depth + 1)
                    }
                }
            } else when (fourCc) {
                "avih" -> {
                    if (chunkSize >= 40L) {
                        val avihBuf = ByteBuffer.allocateDirect(40).order(ByteOrder.LITTLE_ENDIAN)
                        if (source.readAt(dataStart, avihBuf) >= 40) {
                            val usecPerFrame = avihBuf.getInt(0).toLong() and 0xFFFFFFFFL
                            if (usecPerFrame in 5_000L..500_000L) {
                                frameDurationMs = (usecPerFrame / 1000L).coerceAtLeast(10L)
                            }
                            val w = avihBuf.getInt(32)
                            val h = avihBuf.getInt(36)
                            if (w in 16..4096 && h in -4096..4096 && h != 0) {
                                width = w
                                height = kotlin.math.abs(h)
                            }
                        }
                    }
                }
                "strh" -> {
                    if (chunkSize >= 32L) {
                        val strhBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN)
                        val got = source.readAt(dataStart, strhBuf)
                        if (got >= 8) {
                            val stBytes = ByteArray(4)
                            strhBuf.position(0)
                            strhBuf.get(stBytes)
                            currentStreamType = String(stBytes, StandardCharsets.US_ASCII)

                            val handlerBytes = ByteArray(4)
                            strhBuf.position(4)
                            strhBuf.get(handlerBytes)
                            val handlerFourCc = String(handlerBytes, StandardCharsets.US_ASCII).trim()
                            if (currentStreamType == "vids" && handlerFourCc.isNotEmpty()) {
                                videoFourCc = handlerFourCc
                                videoMimeType = mapVideoFourCcToMime(handlerFourCc)
                            }
                            if (currentStreamType == "vids" && got >= 28) {
                                val dwScale = strhBuf.getInt(20).toLong() and 0xFFFFFFFFL
                                val dwRate = strhBuf.getInt(24).toLong() and 0xFFFFFFFFL
                                if (dwScale > 0L && dwRate > dwScale) {
                                    val fps = dwRate.toDouble() / dwScale.toDouble()
                                    if (fps in 2.0..120.0) {
                                        frameDurationMs = (1000.0 / fps).toLong().coerceIn(8L, 500L)
                                    }
                                }
                            }
                        }
                    }
                }
                "strf" -> {
                    if (currentStreamType == "vids" && chunkSize >= 40L) {
                        val bmiLen = minOf(chunkSize, 4096L).toInt()
                        val bmiBuf = ByteBuffer.allocateDirect(bmiLen).order(ByteOrder.LITTLE_ENDIAN)
                        val got = source.readAt(dataStart, bmiBuf)
                        if (got >= 40) {
                            val biW = bmiBuf.getInt(4)
                            val biH = bmiBuf.getInt(8)
                            if (biW in 16..4096 && biH in -4096..4096 && biH != 0) {
                                width = biW
                                height = kotlin.math.abs(biH)
                            }
                            val compBytes = ByteArray(4)
                            bmiBuf.position(16)
                            bmiBuf.get(compBytes)
                            val compFourCc = String(compBytes, StandardCharsets.US_ASCII).trim()
                            if (compFourCc.any { it.isLetterOrDigit() }) {
                                videoFourCc = compFourCc
                                mapVideoFourCcToMime(compFourCc)?.let { videoMimeType = it }
                            }
                            if (got > 40) {
                                val extra = ByteArray(got - 40)
                                bmiBuf.position(40)
                                bmiBuf.get(extra)
                                videoCodecExtraData = extra
                            }
                        }
                    } else if (currentStreamType == "auds" && chunkSize >= 14L) {
                        val wavLen = minOf(chunkSize, 1024L).toInt()
                        val wavBuf = ByteBuffer.allocateDirect(wavLen).order(ByteOrder.LITTLE_ENDIAN)
                        val got = source.readAt(dataStart, wavBuf)
                        if (got >= 14) {
                            audioFormatTag = wavBuf.getShort(0).toInt() and 0xFFFF
                            audioMimeType = mapAudioTagToMime(audioFormatTag)
                            audioChannels = (wavBuf.getShort(2).toInt() and 0xFFFF).coerceIn(1, 8)
                            audioSampleRate = wavBuf.getInt(4).coerceIn(8000, 96000)
                            audioByteRate = wavBuf.getInt(8).coerceAtLeast(0)
                            if (got >= 16) {
                                val bps = wavBuf.getShort(14).toInt() and 0xFFFF
                                if (bps in setOf(8, 16, 24, 32)) audioBitsPerSample = bps
                            }
                            if (got > 18) {
                                val cbSize = (wavBuf.getShort(16).toInt() and 0xFFFF).coerceIn(0, got - 18)
                                if (cbSize > 0) {
                                    val extra = ByteArray(cbSize)
                                    wavBuf.position(18)
                                    wavBuf.get(extra)
                                    audioCodecExtraData = extra
                                }
                            }
                        }
                    }
                }
                else -> {
                    if (fourCc.length == 4 && fourCc[0].isDigit() && fourCc[1].isDigit()) {
                        val suffix = fourCc.substring(2).lowercase(Locale.ROOT)
                        if (suffix == "dc" || suffix == "db") {
                            if (chunkSize in 4L..(8L * 1024L * 1024L)) {
                                videoChunks.add(AviChunkEntry(dataStart, chunkSize.toInt(), true))
                            }
                        } else if (suffix == "wb") {
                            if (chunkSize in 1L..(4L * 1024L * 1024L)) {
                                audioChunks.add(AviChunkEntry(dataStart, chunkSize.toInt(), false))
                            }
                        }
                    }
                }
            }

            if (nextPos <= pos) break
            pos = nextPos
        }
    }

    fun readChunkBytes(entry: AviChunkEntry): ByteArray? {
        if (entry.size <= 0) return null
        val buf = ByteBuffer.allocateDirect(entry.size)
        val r = source.readAt(entry.offset, buf)
        if (r <= 0) return null
        val bytes = ByteArray(r)
        buf.flip()
        buf.get(bytes)
        return bytes
    }

    fun decodeVideoFrameAt(frameIndex: Int): Bitmap? {
        if (videoChunks.isEmpty()) return null
        val entry = videoChunks[frameIndex.coerceIn(0, videoChunks.lastIndex)]
        val bytes = readChunkBytes(entry) ?: return null

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded != null) return decoded

        val pixelCount = width * height
        if ((videoMimeType == null || videoFourCc.isEmpty() || videoFourCc == "DIB ") &&
            width in 16..2048 && height in 16..2048 && bytes.size >= pixelCount * 3
        ) {
            val pixels = IntArray(pixelCount)
            val bpp = if (bytes.size >= pixelCount * 4) 4 else 3
            val stride = width * bpp
            for (y in 0 until height) {
                val srcRow = (height - 1 - y) * stride
                val dstRow = y * width
                for (x in 0 until width) {
                    val p = srcRow + x * bpp
                    if (p + 2 >= bytes.size) break
                    val b = bytes[p].toInt() and 0xFF
                    val g = bytes[p + 1].toInt() and 0xFF
                    val rCol = bytes[p + 2].toInt() and 0xFF
                    pixels[dstRow + x] = Color.rgb(rCol, g, b)
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        val mime = videoMimeType
        if (mime != null && mime != "video/mjpeg") {
            return decodeCompressedFrameViaMediaCodec(mime, frameIndex)
        }
        return null
    }

    private fun decodeCompressedFrameViaMediaCodec(mime: String, targetFrameIndex: Int): Bitmap? {
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            videoCodecExtraData?.let { extra ->
                if (extra.isNotEmpty()) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(extra))
                }
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val startFeed = 0
            val maxFeed = minOf(videoChunks.size - 1, maxOf(targetFrameIndex, 12))

            for (idx in startFeed..maxFeed) {
                val chunkBytes = readChunkBytes(videoChunks[idx]) ?: continue
                val inIdx = codec.dequeueInputBuffer(15_000L)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)
                    inBuf?.clear()
                    inBuf?.put(chunkBytes)
                    codec.queueInputBuffer(inIdx, 0, chunkBytes.size, idx * frameDurationMs * 1000L, 0)
                }
                val outIdx = codec.dequeueOutputBuffer(info, 15_000L)
                if (outIdx >= 0) {
                    val image = try { codec.getOutputImage(outIdx) } catch (_: Throwable) { null }
                    val bmp = if (image != null) {
                        try { yuv420ImageToBitmap(image) } finally { image.close() }
                    } else null
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bmp != null) return bmp
                }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
        }
    }

    private fun yuv420ImageToBitmap(image: android.media.Image): Bitmap? {
        val w = image.width
        val h = image.height
        val planes = image.planes
        if (planes.size < 3) return null
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val yRow = y * yRowStride
            val uvRow = (y shr 1) * uvRowStride
            for (x in 0 until w) {
                val yVal = (yBuf.get(yRow + x).toInt() and 0xFF)
                val uvOffset = uvRow + (x shr 1) * uvPixelStride
                val uVal = (uBuf.get(uvOffset).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(uvOffset).toInt() and 0xFF) - 128

                val r = (yVal + 1.370705f * vVal).toInt().coerceIn(0, 255)
                val g = (yVal - 0.337633f * uVal - 0.698001f * vVal).toInt().coerceIn(0, 255)
                val b = (yVal + 1.732446f * uVal).toInt().coerceIn(0, 255)
                pixels[y * w + x] = Color.rgb(r, g, b)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}

internal class HybridCleanRoomMediaPlayer : MediaPlayer() {
    @Volatile var isSoftwareAviMode = false
        private set

    @Volatile var targetAudioSampleRate: Int = 0
    @Volatile var targetAudioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    fun applyAudioQualityConfig(sampleRateHz: Int, bitDepth: Int) {
        targetAudioSampleRate = sampleRateHz
        targetAudioEncoding = if (bitDepth >= 24) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val currentParams = try { playbackParams } catch (_: Throwable) { null } ?: android.media.PlaybackParams()
                setPlaybackParams(currentParams)
            }
        } catch (_: Throwable) {}
    }

    private var aviDemuxer: CleanRoomAviDemuxer? = null
    private var fallbackFd: FileDescriptor? = null
    private var fallbackFileSize: Long = -1L
    @Volatile var previewFallbackBitmap: Bitmap? = null
    @Volatile private var attachedSurface: Surface? = null
    @Volatile private var swPlaying = false
    @Volatile private var swCurrentPositionMs = 0
    @Volatile private var swCompletionListener: OnCompletionListener? = null

    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun activateCleanRoomAviMode(demuxer: CleanRoomAviDemuxer): Boolean {
        val hasHardwareVideoCodec = demuxer.videoMimeType != null &&
                demuxer.videoMimeType != "video/mjpeg" &&
                demuxer.videoChunks.isNotEmpty()
        val hasSoftwareVideoFrame = !hasHardwareVideoCodec && demuxer.decodeVideoFrameAt(0)?.also {
            if (previewFallbackBitmap == null) previewFallbackBitmap = it else it.recycle()
        } != null
        val hasAudioTrack = demuxer.audioChunks.isNotEmpty() &&
                (demuxer.audioSampleRate > 0 || demuxer.audioMimeType != null)

        if (!hasHardwareVideoCodec && !hasSoftwareVideoFrame && !hasAudioTrack) {
            return false
        }
        aviDemuxer = demuxer
        isSoftwareAviMode = true
        return true
    }

    fun activateMediaExtractorCodecFallback(fd: FileDescriptor, fileSize: Long): Pair<Boolean, Int>? {
        val extractor = MediaExtractor()
        return try {
            if (fileSize > 0L) extractor.setDataSource(fd, 0L, fileSize) else extractor.setDataSource(fd)
            if (extractor.trackCount <= 0) return null
            var hasVid = false
            var maxDurUs = 0L
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) hasVid = true
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    maxDurUs = maxOf(maxDurUs, fmt.getLong(MediaFormat.KEY_DURATION))
                }
            }
            fallbackFd = fd
            fallbackFileSize = fileSize
            isSoftwareAviMode = true
            val durMs = ((maxDurUs / 1000L).toInt()).coerceAtLeast(1000)
            hasVid to durMs
        } catch (_: Throwable) {
            null
        } finally {
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun drawBitmapCenteredOnSurface(surface: Surface, bmp: Bitmap) {
        if (!surface.isValid || bmp.isRecycled) return
        try {
            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(Color.BLACK)
                val cw = canvas.width.toFloat()
                val ch = canvas.height.toFloat()
                val aspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                val targetAspect = cw / ch.coerceAtLeast(1f)
                val dstW: Float
                val dstH: Float
                if (aspect > targetAspect) {
                    dstW = cw
                    dstH = cw / aspect
                } else {
                    dstH = ch
                    dstW = ch * aspect
                }
                val left = (cw - dstW) / 2f
                val top = (ch - dstH) / 2f
                canvas.drawBitmap(bmp, null, RectF(left, top, left + dstW, top + dstH), drawPaint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Throwable) {}
    }

    override fun setSurface(surface: Surface?) {
        attachedSurface = surface
        if (!isSoftwareAviMode) {
            try {
                super.setSurface(surface)
                if (surface != null && surface.isValid && !super.isPlaying()) {
                    val pos = super.getCurrentPosition()
                    if (pos <= 0) {
                        super.seekTo(1)
                        super.seekTo(0)
                    } else {
                        super.seekTo(pos)
                    }
                }
            } catch (_: Throwable) {}
        } else if (surface != null && surface.isValid && !swPlaying) {
            val demux = aviDemuxer
            if (demux != null && demux.videoChunks.isNotEmpty()) {
                playbackScope.launch {
                    renderSingleAviFrameToSurface(swCurrentPositionMs)
                }
            } else {
                val preview = previewFallbackBitmap
                if (preview != null && !preview.isRecycled) {
                    drawBitmapCenteredOnSurface(surface, preview)
                }
            }
        }
    }

    override fun setOnCompletionListener(listener: OnCompletionListener?) {
        swCompletionListener = listener
        try { super.setOnCompletionListener(listener) } catch (_: Throwable) {}
    }

    override fun isPlaying(): Boolean {
        return if (isSoftwareAviMode) swPlaying else try { super.isPlaying() } catch (_: Throwable) { false }
    }

    override fun getDuration(): Int {
        return if (isSoftwareAviMode) {
            (aviDemuxer?.totalDurationMs ?: 1000).coerceAtLeast(1)
        } else {
            try { super.getDuration() } catch (_: Throwable) { 1 }
        }
    }

    override fun getCurrentPosition(): Int {
        return if (isSoftwareAviMode) swCurrentPositionMs else try { super.getCurrentPosition() } catch (_: Throwable) { 0 }
    }

    override fun seekTo(msec: Int) {
        if (isSoftwareAviMode) {
            val dur = duration
            swCurrentPositionMs = msec.coerceIn(0, dur)
            val demux = aviDemuxer
            if (demux != null && !swPlaying) {
                playbackScope.launch {
                    renderSingleAviFrameToSurface(swCurrentPositionMs)
                }
            }
        } else {
            try { super.seekTo(msec) } catch (_: Throwable) {}
        }
    }

    override fun start() {
        if (isSoftwareAviMode) {
            if (swPlaying) return
            val demux = aviDemuxer
            if (demux != null) {
                if (swCurrentPositionMs >= demux.totalDurationMs) {
                    swCurrentPositionMs = 0
                }
                swPlaying = true
                startAviPlaybackJobs(demux)
            } else if (fallbackFd != null) {
                swPlaying = true
                startExtractorCodecPlayback(fallbackFd!!, fallbackFileSize)
            }
        } else {
            super.start()
        }
    }

    override fun pause() {
        if (isSoftwareAviMode) {
            stopActiveJobs()
        } else {
            super.pause()
        }
    }

    override fun stop() {
        if (isSoftwareAviMode) {
            stopActiveJobs()
        } else {
            super.stop()
        }
    }

    override fun release() {
        stopActiveJobs()
        try { playbackScope.cancel() } catch (_: Throwable) {}
        aviDemuxer = null
        fallbackFd = null
        previewFallbackBitmap = null
        try { super.release() } catch (_: Throwable) {}
    }

    private fun stopActiveJobs() {
        swPlaying = false
        videoJob?.cancel()
        audioJob?.cancel()
        videoJob = null
        audioJob = null
    }

    private fun renderSingleAviFrameToSurface(positionMs: Int) {
        val demux = aviDemuxer ?: return
        val surface = attachedSurface ?: return
        if (!surface.isValid || demux.videoChunks.isEmpty()) return

        val frameIdx = ((positionMs.toLong() / demux.frameDurationMs.coerceAtLeast(1L)).toInt())
            .coerceIn(0, demux.videoChunks.lastIndex)
        val bmp = demux.decodeVideoFrameAt(frameIdx) ?: previewFallbackBitmap
        if (bmp != null && !bmp.isRecycled) {
            drawBitmapCenteredOnSurface(surface, bmp)
            if (bmp !== previewFallbackBitmap) {
                bmp.recycle()
            }
        }
    }

    private fun startAviPlaybackJobs(demux: CleanRoomAviDemuxer) {
        videoJob?.cancel()
        audioJob?.cancel()

        if (demux.audioChunks.isNotEmpty() && demux.audioSampleRate > 0) {
            audioJob = playbackScope.launch {
                runAviAudioPlaybackLoop(demux)
            }
        }

        videoJob = playbackScope.launch {
            val vMime = demux.videoMimeType
            val surface = attachedSurface
            var hwSucceeded = false
            if (vMime != null && vMime != "video/mjpeg" && surface != null && surface.isValid && demux.videoChunks.isNotEmpty()) {
                hwSucceeded = runAviHardwareVideoCodecLoop(demux, vMime, surface)
            }
            if (!hwSucceeded && swPlaying && isActive) {
                runAviSoftwareVideoLoop(demux)
            }
        }
    }

    private suspend fun runAviHardwareVideoCodecLoop(
        demux: CleanRoomAviDemuxer,
        mime: String,
        surface: Surface
    ): Boolean {
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createVideoFormat(mime, demux.width, demux.height)
            demux.videoCodecExtraData?.let { extra ->
                if (extra.isNotEmpty()) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(extra))
                }
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, surface, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val frameStepMs = demux.frameDurationMs.coerceIn(10L, 200L)
            var chunkIdx = ((swCurrentPositionMs.toLong() / frameStepMs).toInt()).coerceIn(0, demux.videoChunks.lastIndex)
            val basePtsUs = chunkIdx * frameStepMs * 1000L
            val baseWallClockNs = System.nanoTime()
            var inputEos = false

            while (swPlaying && currentCoroutineContext().isActive) {
                if (!inputEos && chunkIdx < demux.videoChunks.size) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val chunkBytes = demux.readChunkBytes(demux.videoChunks[chunkIdx])
                        val inBuf = codec.getInputBuffer(inIdx)
                        inBuf?.clear()
                        if (chunkBytes != null && chunkBytes.isNotEmpty()) {
                            inBuf?.put(chunkBytes)
                            val ptsUs = chunkIdx * frameStepMs * 1000L
                            codec.queueInputBuffer(inIdx, 0, chunkBytes.size, ptsUs, 0)
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                        }
                        chunkIdx++
                        if (chunkIdx >= demux.videoChunks.size) {
                            inputEos = true
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val renderToSurface = surface.isValid && info.size > 0
                    val relativePtsNs = ((info.presentationTimeUs - basePtsUs).coerceAtLeast(0L)) * 1_000L
                    val targetRenderNs = baseWallClockNs + relativePtsNs
                    if (renderToSurface) {
                        codec.releaseOutputBuffer(outIdx, targetRenderNs)
                    } else {
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    swCurrentPositionMs = ((info.presentationTimeUs / 1000L).toInt()).coerceIn(0, demux.totalDurationMs)

                    val waitMs = ((targetRenderNs - System.nanoTime()) / 1_000_000L).coerceIn(2L, frameStepMs)
                    delay(waitMs)
                } else if (inputEos) {
                    swPlaying = false
                    swCurrentPositionMs = demux.totalDurationMs
                    mainHandler.post {
                        try { swCompletionListener?.onCompletion(this) } catch (_: Throwable) {}
                    }
                    break
                }
            }
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
        }
    }

    private suspend fun runAviSoftwareVideoLoop(demux: CleanRoomAviDemuxer) {
        val frameStepMs = demux.frameDurationMs.coerceIn(10L, 200L)
        val frameStepNs = frameStepMs * 1_000_000L
        var nextFrameTargetNs = System.nanoTime()

        while (swPlaying && currentCoroutineContext().isActive) {
            renderSingleAviFrameToSurface(swCurrentPositionMs)
            swCurrentPositionMs += frameStepMs.toInt()
            if (swCurrentPositionMs >= demux.totalDurationMs) {
                swPlaying = false
                swCurrentPositionMs = demux.totalDurationMs
                mainHandler.post {
                    try { swCompletionListener?.onCompletion(this) } catch (_: Throwable) {}
                }
                break
            }
            nextFrameTargetNs += frameStepNs
            val nowNs = System.nanoTime()
            val sleepMs = ((nextFrameTargetNs - nowNs) / 1_000_000L).coerceIn(4L, frameStepMs)
            if (nowNs > nextFrameTargetNs + frameStepNs) {
                nextFrameTargetNs = nowNs
            }
            delay(sleepMs)
        }
    }

    private suspend fun runAviAudioPlaybackLoop(demux: CleanRoomAviDemuxer) {
        var audioTrack: AudioTrack? = null
        var audioCodec: MediaCodec? = null
        try {
            val channelConfig = if (demux.audioChannels >= 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }
            val pcmEncoding = if (demux.audioBitsPerSample == 8 && demux.audioFormatTag == 1) {
                AudioFormat.ENCODING_PCM_8BIT
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            val sampleRateToUse = if (targetAudioSampleRate > 0) targetAudioSampleRate else demux.audioSampleRate
            val pcmEncodingToUse = if (targetAudioEncoding != 0) targetAudioEncoding else pcmEncoding

            val minBuf = AudioTrack.getMinBufferSize(sampleRateToUse, channelConfig, pcmEncodingToUse)
                .coerceAtLeast(8192)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateToUse)
                        .setChannelMask(channelConfig)
                        .setEncoding(pcmEncodingToUse)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .build()
            audioTrack.play()

            val totalAudioChunks = demux.audioChunks.size
            val startChunkIdx = if (demux.totalDurationMs > 0) {
                ((swCurrentPositionMs.toLong() * totalAudioChunks) / demux.totalDurationMs).toInt()
                    .coerceIn(0, (totalAudioChunks - 1).coerceAtLeast(0))
            } else 0

            val mime = demux.audioMimeType
            if (demux.audioFormatTag == 1 || mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                for (i in startChunkIdx until totalAudioChunks) {
                    if (!swPlaying || !currentCoroutineContext().isActive) break
                    val pcmBytes = demux.readChunkBytes(demux.audioChunks[i]) ?: continue
                    audioTrack.write(pcmBytes, 0, pcmBytes.size)
                }
            } else if (mime != null) {
                val format = MediaFormat.createAudioFormat(mime, demux.audioSampleRate, demux.audioChannels.coerceIn(1, 2))
                demux.audioCodecExtraData?.let { extra ->
                    if (extra.isNotEmpty()) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(extra))
                    }
                }
                audioCodec = MediaCodec.createDecoderByType(mime)
                audioCodec.configure(format, null, null, 0)
                audioCodec.start()

                val info = MediaCodec.BufferInfo()
                var cIdx = startChunkIdx
                while (swPlaying && currentCoroutineContext().isActive && cIdx < totalAudioChunks) {
                    val inIdx = audioCodec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val packet = demux.readChunkBytes(demux.audioChunks[cIdx])
                        val inBuf = audioCodec.getInputBuffer(inIdx)
                        inBuf?.clear()
                        if (packet != null && packet.isNotEmpty()) {
                            inBuf?.put(packet)
                            audioCodec.queueInputBuffer(inIdx, 0, packet.size, 0L, 0)
                        } else {
                            audioCodec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                        }
                        cIdx++
                    }

                    var outIdx = audioCodec.dequeueOutputBuffer(info, 10_000L)
                    while (outIdx >= 0 && swPlaying && currentCoroutineContext().isActive) {
                        val outBuf = audioCodec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val pcmChunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(pcmChunk, 0, info.size)
                            audioTrack.write(pcmChunk, 0, pcmChunk.size)
                        }
                        audioCodec.releaseOutputBuffer(outIdx, false)
                        outIdx = audioCodec.dequeueOutputBuffer(info, 0L)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        } finally {
            try { audioCodec?.stop() } catch (_: Throwable) {}
            try { audioCodec?.release() } catch (_: Throwable) {}
            try { audioTrack?.stop() } catch (_: Throwable) {}
            try { audioTrack?.release() } catch (_: Throwable) {}
        }
    }

    private fun startExtractorCodecPlayback(fd: FileDescriptor, fileSize: Long) {
        videoJob?.cancel()
        videoJob = playbackScope.launch {
            val extractor = MediaExtractor()
            var videoCodec: MediaCodec? = null
            try {
                if (fileSize > 0L) extractor.setDataSource(fd, 0L, fileSize) else extractor.setDataSource(fd)
                var videoTrackIdx = -1
                var videoFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoTrackIdx = i
                        videoFormat = fmt
                        break
                    }
                }
                val surface = attachedSurface
                if (videoTrackIdx >= 0 && videoFormat != null && surface != null && surface.isValid) {
                    extractor.selectTrack(videoTrackIdx)
                    val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
                    videoCodec = MediaCodec.createDecoderByType(mime)
                    videoCodec.configure(videoFormat, surface, null, 0)
                    videoCodec.start()

                    val info = MediaCodec.BufferInfo()
                    val sampleBuf = ByteBuffer.allocateDirect(1024 * 1024)
                    val baseWallClockNs = System.nanoTime()
                    var firstPtsUs = -1L
                    var eos = false

                    while (swPlaying && currentCoroutineContext().isActive && !eos) {
                        val inIdx = videoCodec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            sampleBuf.clear()
                            val sampleSize = extractor.readSampleData(sampleBuf, 0)
                            if (sampleSize < 0) {
                                videoCodec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eos = true
                            } else {
                                val inBuf = videoCodec.getInputBuffer(inIdx)
                                inBuf?.clear()
                                sampleBuf.limit(sampleSize)
                                sampleBuf.position(0)
                                inBuf?.put(sampleBuf)
                                videoCodec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                        val outIdx = videoCodec.dequeueOutputBuffer(info, 10_000L)
                        if (outIdx >= 0) {
                            if (firstPtsUs < 0L) {
                                firstPtsUs = info.presentationTimeUs.coerceAtLeast(0L)
                            }
                            val relPtsNs = ((info.presentationTimeUs - firstPtsUs).coerceAtLeast(0L)) * 1_000L
                            val targetRenderNs = baseWallClockNs + relPtsNs
                            if (surface.isValid && info.size > 0) {
                                videoCodec.releaseOutputBuffer(outIdx, targetRenderNs)
                            } else {
                                videoCodec.releaseOutputBuffer(outIdx, false)
                            }
                            swCurrentPositionMs = (info.presentationTimeUs / 1000L).toInt()
                            val waitMs = ((targetRenderNs - System.nanoTime()) / 1_000_000L).coerceIn(2L, 33L)
                            delay(waitMs)
                        }
                    }
                    swPlaying = false
                    mainHandler.post {
                        try { swCompletionListener?.onCompletion(this@HybridCleanRoomMediaPlayer) } catch (_: Throwable) {}
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            } finally {
                try { videoCodec?.stop() } catch (_: Throwable) {}
                try { videoCodec?.release() } catch (_: Throwable) {}
                try { extractor.release() } catch (_: Throwable) {}
            }
        }
    }
}

class ZeroCopyMediaEngineImpl : FileEngineProvider {
    companion object {
        val SUPPORTED_MEDIA_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "mp3", "wav", "aac", "flac", "m4a", "ogg"
        )
    }

    override fun canHandle(signature: ByteArray, extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        if (ext in setOf("avif", "heic", "heif")) return false
        if (ext in SUPPORTED_MEDIA_EXTENSIONS) return true

        if (signature.size >= 12) {
            val b0 = signature[0].toInt() and 0xFF
            val b1 = signature[1].toInt() and 0xFF
            val b2 = signature[2].toInt() and 0xFF
            val b3 = signature[3].toInt() and 0xFF

            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) {
                val subType = String(signature, 8, 4, StandardCharsets.US_ASCII)
                if (subType == "WAVE" || subType == "AVI ") return true
            }

            if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return true
            if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return true
            if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) return true

            if (signature[4] == 'f'.code.toByte() &&
                signature[5] == 't'.code.toByte() &&
                signature[6] == 'y'.code.toByte() &&
                signature[7] == 'p'.code.toByte()
            ) {
                val boxSize = ByteBuffer.wrap(signature, 0, 4).order(ByteOrder.BIG_ENDIAN).int.coerceIn(12, min(signature.size, 64))
                val ftypHeader = String(signature, 8, (boxSize - 8).coerceAtLeast(4), StandardCharsets.US_ASCII).lowercase(Locale.ROOT)
                val majorBrand = ftypHeader.take(4)
                if (majorBrand !in setOf("avif", "avis", "heic", "heix", "hevc", "mif1", "msf1") &&
                    !ftypHeader.contains("avif") && !ftypHeader.contains("heic")
                ) {
                    return true
                }
            }

            if ((b0 == 0x49 && b1 == 0x44 && b2 == 0x33) || (b0 == 0xFF && (b1 and 0xE0) == 0xE0)) {
                return true
            }
        }
        return false
    }

    override fun createSession(fd: FileDescriptor): DocumentSession {
        val pfd = ParcelFileDescriptor.dup(fd)
        return MediaPlaybackSession(pfd)
    }
}

class MediaPlaybackSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile private var isClosed = false
    private val hybridPlayer = HybridCleanRoomMediaPlayer()
    val mediaPlayer: MediaPlayer get() = hybridPlayer

    fun updateAudioQualityConfig(sampleRateHz: Int, bitDepth: Int) {
        hybridPlayer.applyAudioQualityConfig(sampleRateHz, bitDepth)
    }

    private val _isPrepared = MutableStateFlow(false)
    val isPrepared: StateFlow<Boolean> = _isPrepared.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    var trackTitle: String = "Аудио / Видео файл"
        private set
    var trackArtist: String = "Неизвестный исполнитель"
        private set
    var albumArt: Bitmap? = null
        private set
    var durationMs: Int = 0
        private set
    var hasVideo: Boolean = false
        private set

    private fun resolveFdSize(fd: FileDescriptor): Long {
        try {
            val statSize = Os.fstat(fd).st_size
            if (statSize > 0L) return statSize
        } catch (_: Throwable) {}
        return try {
            val cur = Os.lseek(fd, 0L, OsConstants.SEEK_CUR)
            val end = Os.lseek(fd, 0L, OsConstants.SEEK_END)
            Os.lseek(fd, cur, OsConstants.SEEK_SET)
            if (end > 0L) end else -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun tryActivateCleanRoomAviOrExtractorFallback(fileSize: Long): Boolean {
        return try {
            val source = ZeroCacheReadableSource(pfd.fileDescriptor)
            val demuxer = CleanRoomAviDemuxer(source)
            if (demuxer.isValidAvi && hybridPlayer.activateCleanRoomAviMode(demuxer)) {
                hasVideo = demuxer.videoChunks.isNotEmpty()
                durationMs = demuxer.totalDurationMs
                if (albumArt == null && hasVideo) {
                    albumArt = demuxer.decodeVideoFrameAt(0)
                }
                hybridPlayer.previewFallbackBitmap = albumArt
                _playbackError.value = null
                _isPrepared.value = true
                return true
            }

            val extFallback = hybridPlayer.activateMediaExtractorCodecFallback(pfd.fileDescriptor, fileSize)
            if (extFallback != null) {
                hasVideo = extFallback.first
                durationMs = extFallback.second
                hybridPlayer.previewFallbackBitmap = albumArt
                _playbackError.value = null
                _isPrepared.value = true
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    init {
        val fd = pfd.fileDescriptor
        val fileSize = resolveFdSize(fd)

        if (fileSize == 0L) {
            _playbackError.value = "Медиафайл повреждён или пуст (0 байт)."
            try { hybridPlayer.release() } catch (_: Exception) {}
        } else {
            var metadataFound = false
            val retriever = MediaMetadataRetriever()
            try {
                try { Os.lseek(fd, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}
                if (fileSize > 0L) {
                    retriever.setDataSource(fd, 0L, fileSize)
                } else {
                    retriever.setDataSource(fd)
                }

                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val hasVideoStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                val hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                val videoWidthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val artBytes = retriever.embeddedPicture

                if (!title.isNullOrEmpty()) trackTitle = title
                if (!artist.isNullOrEmpty()) trackArtist = artist
                if (!dur.isNullOrEmpty()) durationMs = dur.toIntOrNull() ?: 0
                if (hasVideoStr == "yes" || (videoWidthStr?.toIntOrNull() ?: 0) > 0) {
                    hasVideo = true
                }
                if (hasVideoStr == "yes" || hasAudioStr == "yes" || dur != null) {
                    metadataFound = true
                }

                if (artBytes != null && artBytes.isNotEmpty()) {
                    albumArt = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                } else if (hasVideo) {
                    albumArt = try {
                        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Throwable) {
                        null
                    }
                }
                hybridPlayer.previewFallbackBitmap = albumArt
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            try {
                try { Os.lseek(fd, 0L, OsConstants.SEEK_SET) } catch (_: Throwable) {}

                try {
                    val mediaAudioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    hybridPlayer.setAudioAttributes(mediaAudioAttributes)
                } catch (_: Throwable) {}

                hybridPlayer.setOnErrorListener { _, what, extra ->
                    synchronized(this@MediaPlaybackSession) {
                        if (!isClosed) {
                            if (!tryActivateCleanRoomAviOrExtractorFallback(fileSize)) {
                                _isPrepared.value = false
                                _playbackError.value = "Сбой декодирования медиапотока (код $what / $extra). Формат или кодек не поддерживается устройством."
                            }
                        }
                    }
                    true
                }
                hybridPlayer.setOnPreparedListener { mp ->
                    synchronized(this@MediaPlaybackSession) {
                        if (!isClosed) {
                            if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                hasVideo = true
                            }
                            if (durationMs <= 0 && mp.duration > 0) {
                                durationMs = mp.duration
                            }
                            if (hasVideo && !mp.isPlaying) {
                                try {
                                    mp.seekTo(1)
                                    mp.seekTo(0)
                                } catch (_: Throwable) {}
                            }
                            _isPrepared.value = true
                        }
                    }
                }

                if (fileSize > 0L) {
                    hybridPlayer.setDataSource(fd, 0L, fileSize)
                } else {
                    hybridPlayer.setDataSource(fd)
                }

                if (Looper.myLooper() != Looper.getMainLooper()) {
                    hybridPlayer.prepare()
                    synchronized(this@MediaPlaybackSession) {
                        if (!isClosed) {
                            if (hybridPlayer.videoWidth > 0 && hybridPlayer.videoHeight > 0) {
                                hasVideo = true
                            }
                            if (durationMs <= 0 && hybridPlayer.duration > 0) {
                                durationMs = hybridPlayer.duration
                            }
                            if (hasVideo && !hybridPlayer.isPlaying) {
                                try {
                                    hybridPlayer.seekTo(1)
                                    hybridPlayer.seekTo(0)
                                } catch (_: Throwable) {}
                            }
                            _isPrepared.value = true
                        }
                    }
                } else {
                    hybridPlayer.prepareAsync()
                }
            } catch (e: Exception) {
                if (!tryActivateCleanRoomAviOrExtractorFallback(fileSize)) {
                    _isPrepared.value = false
                    val msg = if (!metadataFound) {
                        "Медиафайл повреждён или использует неподдерживаемый кодек: ${e.localizedMessage ?: "Unsupported codec"}"
                    } else {
                        "Не удалось инициализировать медиаплеер: ${e.localizedMessage ?: "I/O Error"}"
                    }
                    _playbackError.value = msg
                    try { hybridPlayer.release() } catch (_: Exception) {}
                }
            }
        }
    }

    override val pageCount: Int = 1

    override fun getLastError(): String? = _playbackError.value

    override fun renderTile(pageIndex: Int, tileX: Int, tileY: Int, zoomPermille: Int, bitmap: Bitmap) {
        if (isClosed) return
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x12, 0x13, 0x1A))
        val preview = albumArt
        if (preview != null && !preview.isRecycled) {
            val cw = bitmap.width.toFloat()
            val ch = bitmap.height.toFloat()
            val aspect = preview.width.toFloat() / preview.height.toFloat().coerceAtLeast(1f)
            val targetAspect = cw / ch.coerceAtLeast(1f)
            val dstW = if (aspect > targetAspect) cw else ch * aspect
            val dstH = if (aspect > targetAspect) cw / aspect else ch
            val left = (cw - dstW) / 2f
            val top = (ch - dstH) / 2f
            canvas.drawBitmap(preview, null, RectF(left, top, left + dstW, top + dstH), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    override fun renderTile(key: PackedTileKey, bitmap: Bitmap) {
        renderTile(0, key.x, key.y, 1000, bitmap)
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        val wasPrepared = _isPrepared.value
        _isPrepared.value = false
        try {
            hybridPlayer.setOnPreparedListener(null)
            hybridPlayer.setOnErrorListener(null)
            hybridPlayer.setOnCompletionListener(null)
        } catch (_: Exception) {}
        try {
            if (wasPrepared && hybridPlayer.isPlaying) {
                hybridPlayer.stop()
            }
        } catch (_: Exception) {}
        try {
            hybridPlayer.release()
        } catch (_: Exception) {}
        try {
            albumArt?.recycle()
            albumArt = null
        } catch (_: Exception) {}
        try {
            pfd.close()
        } catch (_: Exception) {}
    }
}