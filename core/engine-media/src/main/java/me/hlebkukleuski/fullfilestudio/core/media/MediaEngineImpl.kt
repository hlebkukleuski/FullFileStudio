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

package me.hlebkukleuski.fullfilestudio.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.min
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
import me.hlebkukleuski.fullfilestudio.core.engine.DocumentSession
import me.hlebkukleuski.fullfilestudio.core.engine.FileEngineProvider
import me.hlebkukleuski.fullfilestudio.core.engine.PackedTileKey
import me.hlebkukleuski.fullfilestudio.core.security.ZeroCacheReadableSource

internal class AviDemuxer(private val source: ZeroCacheReadableSource) {
    class AviChunkEntry(val offset: Long, val size: Int)

    class AudioStream(val streamNo: Int) {
        var formatTag = 0
        var mimeType: String? = null
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 16
        var byteRate = 0
        var blockAlign = 0
        var scale = 0L
        var rate = 0L
        var sampleSize = 0
        var extra: ByteArray? = null
        var name: String? = null
        val chunks = ArrayList<AviChunkEntry>()
        private var cumulative: LongArray? = null

        val isRawPcm: Boolean get() = formatTag == 1 || formatTag == 3
        val isAdpcm: Boolean get() = formatTag == 2 || formatTag == 0x11
        private val perChunkTiming: Boolean get() = !isRawPcm && !isAdpcm && sampleSize == 0 && scale > 0 && rate > 0

        private fun bytesPerSecond(): Long {
            if (byteRate > 0) return byteRate.toLong()
            return (sampleRate.coerceAtLeast(8000).toLong() * channels.coerceAtLeast(1) * (bitsPerSample / 8).coerceAtLeast(1)).coerceAtLeast(1L)
        }

        private fun cumulativeBytes(): LongArray {
            cumulative?.let { return it }
            val arr = LongArray(chunks.size + 1)
            for (i in chunks.indices) arr[i + 1] = arr[i] + chunks[i].size
            cumulative = arr
            return arr
        }

        fun chunkStartUs(i: Int): Long {
            val k = i.coerceIn(0, chunks.size)
            if (perChunkTiming) return k.toLong() * scale * 1_000_000L / rate
            return cumulativeBytes()[k] * 1_000_000L / bytesPerSecond()
        }

        val durationUs: Long get() = chunkStartUs(chunks.size)

        fun chunkAt(us: Long): Int {
            var lo = 0
            var hi = chunks.size - 1
            var ans = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (chunkStartUs(mid) <= us) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return ans
        }
    }

    class TextStream(val streamNo: Int) {
        var name: String? = null
        val chunks = ArrayList<AviChunkEntry>()
    }

    var width: Int = 640
        private set
    var height: Int = 480
        private set
    var frameDurationUs: Long = 33_333L
        private set
    var totalDurationMs: Int = 0
        private set
    var videoFourCc: String = ""
        private set
    var videoMimeType: String? = null
        private set
    var videoCodecExtraData: ByteArray? = null
        private set

    val videoChunks = ArrayList<AviChunkEntry>()
    val audioStreams = ArrayList<AudioStream>()
    val textStreams = ArrayList<TextStream>()
    private val audioByNo = HashMap<Int, AudioStream>()
    private val textByNo = HashMap<Int, TextStream>()
    private val streamTypes = HashMap<Int, String>()
    private var videoStreamNo = -1
    private var streamCounter = -1
    private var currentStreamType = ""
    private var videoRateFromHeader = false
    private var idx1Offset = -1L
    private var idx1Size = 0L
    private var keyFlags: BooleanArray? = null
    private var entryCount = 0

    val isValidAvi: Boolean

    init {
        isValidAvi = parseRiffAviStructure()
    }

    private fun mapVideoFourCcToMime(fourCc: String): String? {
        return when (fourCc.uppercase(Locale.ROOT).trim()) {
            "H264", "AVC1", "X264", "DAVC", "VSSH" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "FMP4", "DX50", "XVID", "DIVX", "MP4V", "3IV2" -> MediaFormat.MIMETYPE_VIDEO_MPEG4
            "HEVC", "H265", "X265", "HVC1" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "VP80" -> MediaFormat.MIMETYPE_VIDEO_VP8
            "VP90" -> MediaFormat.MIMETYPE_VIDEO_VP9
            "AV01" -> MediaFormat.MIMETYPE_VIDEO_AV1
            "H263", "S263", "U263" -> MediaFormat.MIMETYPE_VIDEO_H263
            "MPG1", "MPG2", "MPEG" -> MediaFormat.MIMETYPE_VIDEO_MPEG2
            "MJPG", "JPEG", "DMB1", "AVRN", "LJPG" -> "video/mjpeg"
            else -> null
        }
    }

    private fun mapAudioTagToMime(formatTag: Int): String? {
        return when (formatTag) {
            0x0001, 0x0003 -> MediaFormat.MIMETYPE_AUDIO_RAW
            0x0006 -> MediaFormat.MIMETYPE_AUDIO_G711_ALAW
            0x0007 -> MediaFormat.MIMETYPE_AUDIO_G711_MLAW
            0x0050, 0x0055 -> MediaFormat.MIMETYPE_AUDIO_MPEG
            0x00FF, 0x1600, 0x1601, 0x1610, 0x706D, 0x4143 -> MediaFormat.MIMETYPE_AUDIO_AAC
            0x2000 -> MediaFormat.MIMETYPE_AUDIO_AC3
            0x2001 -> "audio/vnd.dts"
            0x566F -> MediaFormat.MIMETYPE_AUDIO_VORBIS
            0xF1AC -> MediaFormat.MIMETYPE_AUDIO_FLAC
            0x6750 -> MediaFormat.MIMETYPE_AUDIO_OPUS
            else -> null
        }
    }

    private fun readBytes(offset: Long, len: Int): ByteArray? {
        if (len <= 0) return null
        val buf = ByteBuffer.allocate(len)
        val r = source.readAt(offset, buf)
        if (r <= 0) return null
        return if (r == len) buf.array() else buf.array().copyOf(r)
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun parseRiffAviStructure(): Boolean {
        val fileSize = source.size
        if (fileSize < 32L) return false
        val head = readBytes(0L, 12) ?: return false
        if (head.size < 12 || String(head, 0, 4, StandardCharsets.US_ASCII) != "RIFF" ||
            String(head, 8, 4, StandardCharsets.US_ASCII) != "AVI "
        ) {
            return false
        }
        walkRiffChunks(12L, fileSize, 0)
        audioStreams.removeAll { it.chunks.isEmpty() }
        parseIdx1()
        val videoMs = videoChunks.size.toLong() * frameDurationUs / 1000L
        val audioMs = (audioStreams.maxOfOrNull { it.durationUs } ?: 0L) / 1000L
        totalDurationMs = maxOf(videoMs, audioMs).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return videoChunks.isNotEmpty() || audioStreams.isNotEmpty()
    }

    private fun walkRiffChunks(startOffset: Long, limitOffset: Long, depth: Int) {
        if (depth > 6) return
        var pos = startOffset
        while (pos + 8L <= limitOffset && entryCount < 1_500_000) {
            val h = readBytes(pos, 12) ?: break
            if (h.size < 8) break
            val fourCc = String(h, 0, 4, StandardCharsets.US_ASCII)
            val chunkSize = le32(h, 4).toLong() and 0xFFFFFFFFL
            val dataStart = pos + 8L
            val nextPos = dataStart + ((chunkSize + 1L) and -2L)

            if (fourCc == "LIST" || fourCc == "RIFF") {
                if (h.size >= 12) {
                    val listType = String(h, 8, 4, StandardCharsets.US_ASCII)
                    if (listType == "strl") {
                        streamCounter++
                        currentStreamType = ""
                    }
                    if (listType in setOf("hdrl", "strl", "movi", "rec ", "AVIX", "AVI ")) {
                        walkRiffChunks(dataStart + 4L, minOf(limitOffset, dataStart + chunkSize), depth + 1)
                    }
                }
            } else when (fourCc) {
                "avih" -> {
                    val b = readBytes(dataStart, 40)
                    if (b != null && b.size >= 40) {
                        val usecPerFrame = le32(b, 0).toLong() and 0xFFFFFFFFL
                        if (!videoRateFromHeader && usecPerFrame in 4_000L..2_000_000L) frameDurationUs = usecPerFrame
                        val w = le32(b, 32)
                        val hh = le32(b, 36)
                        if (w in 16..8192 && hh in -8192..8192 && hh != 0) {
                            width = w
                            height = kotlin.math.abs(hh)
                        }
                    }
                }
                "strh" -> {
                    val b = readBytes(dataStart, minOf(chunkSize, 56L).toInt())
                    if (b != null && b.size >= 8) {
                        currentStreamType = String(b, 0, 4, StandardCharsets.US_ASCII)
                        streamTypes[streamCounter] = currentStreamType
                        val handler = String(b, 4, 4, StandardCharsets.US_ASCII).trim { it <= ' ' }
                        val scale = if (b.size >= 24) le32(b, 20).toLong() and 0xFFFFFFFFL else 0L
                        val rate = if (b.size >= 28) le32(b, 24).toLong() and 0xFFFFFFFFL else 0L
                        when (currentStreamType) {
                            "vids" -> if (videoStreamNo < 0) {
                                videoStreamNo = streamCounter
                                if (handler.isNotEmpty()) {
                                    videoFourCc = handler
                                    videoMimeType = mapVideoFourCcToMime(handler)
                                }
                                if (scale > 0L && rate > 0L) {
                                    val us = scale * 1_000_000L / rate
                                    if (us in 4_000L..2_000_000L) {
                                        frameDurationUs = us
                                        videoRateFromHeader = true
                                    }
                                }
                            }
                            "auds" -> {
                                val st = AudioStream(streamCounter)
                                st.scale = scale
                                st.rate = rate
                                if (b.size >= 48) st.sampleSize = le32(b, 44)
                                audioStreams.add(st)
                                audioByNo[streamCounter] = st
                            }
                            "txts" -> {
                                val ts = TextStream(streamCounter)
                                textStreams.add(ts)
                                textByNo[streamCounter] = ts
                            }
                        }
                    }
                }
                "strf" -> parseStrf(dataStart, chunkSize)
                "strn" -> {
                    val b = readBytes(dataStart, minOf(chunkSize, 256L).toInt())
                    if (b != null) {
                        val end = b.indexOf(0).let { if (it < 0) b.size else it }
                        val name = String(b, 0, end, Charsets.UTF_8).trim()
                        if (name.isNotEmpty()) {
                            audioByNo[streamCounter]?.name = name
                            textByNo[streamCounter]?.name = name
                        }
                    }
                }
                "idx1" -> {
                    idx1Offset = dataStart
                    idx1Size = chunkSize
                }
                else -> collectMoviChunk(fourCc, dataStart, chunkSize)
            }

            if (nextPos <= pos) break
            pos = nextPos
        }
    }

    private fun parseStrf(dataStart: Long, chunkSize: Long) {
        if (currentStreamType == "vids" && streamCounter == videoStreamNo && chunkSize >= 40L) {
            val b = readBytes(dataStart, minOf(chunkSize, 8192L).toInt()) ?: return
            if (b.size < 40) return
            val biW = le32(b, 4)
            val biH = le32(b, 8)
            if (biW in 16..8192 && biH in -8192..8192 && biH != 0) {
                width = biW
                height = kotlin.math.abs(biH)
            }
            val comp = String(b, 16, 4, StandardCharsets.US_ASCII).trim { it <= ' ' }
            if (comp.any { it.isLetterOrDigit() }) {
                videoFourCc = comp
                mapVideoFourCcToMime(comp)?.let { videoMimeType = it }
            }
            if (b.size > 40) videoCodecExtraData = b.copyOfRange(40, b.size)
        } else if (currentStreamType == "auds" && chunkSize >= 14L) {
            val st = audioByNo[streamCounter] ?: return
            val b = readBytes(dataStart, minOf(chunkSize, 4096L).toInt()) ?: return
            if (b.size < 14) return
            var tag = le16(b, 0)
            st.channels = le16(b, 2).coerceIn(1, 8)
            st.sampleRate = le32(b, 4).coerceIn(4000, 384000)
            st.byteRate = le32(b, 8).coerceAtLeast(0)
            st.blockAlign = le16(b, 12)
            if (b.size >= 16) {
                val bps = le16(b, 14)
                if (bps in 2..64) st.bitsPerSample = bps
            }
            if (b.size > 18) {
                val cb = le16(b, 16).coerceIn(0, b.size - 18)
                if (cb > 0) st.extra = b.copyOfRange(18, 18 + cb)
            }
            val ex = st.extra
            if (tag == 0xFFFE && ex != null && ex.size >= 8) tag = le16(ex, 6)
            st.formatTag = tag
            st.mimeType = mapAudioTagToMime(tag)
        }
    }

    private fun collectMoviChunk(fourCc: String, dataStart: Long, chunkSize: Long) {
        if (fourCc.length != 4 || !fourCc[0].isDigit() || !fourCc[1].isDigit()) return
        val no = (fourCc[0] - '0') * 10 + (fourCc[1] - '0')
        val suffix = fourCc.substring(2).lowercase(Locale.ROOT)
        val type = streamTypes[no]
        when {
            (type == "vids" && no == videoStreamNo || type == null && videoStreamNo < 0) && (suffix == "dc" || suffix == "db") -> {
                if (chunkSize <= 16L * 1024L * 1024L) {
                    videoChunks.add(AviChunkEntry(dataStart, chunkSize.toInt()))
                    entryCount++
                }
            }
            type == "auds" || type == null && suffix == "wb" -> {
                val st = audioByNo[no] ?: if (type == null) audioStreams.firstOrNull() ?: AudioStream(no).also {
                    audioStreams.add(it)
                    audioByNo[no] = it
                } else null
                if (st != null && chunkSize in 1L..(8L * 1024L * 1024L)) {
                    st.chunks.add(AviChunkEntry(dataStart, chunkSize.toInt()))
                    entryCount++
                }
            }
            type == "txts" -> {
                if (chunkSize in 1L..(16L * 1024L * 1024L)) textByNo[no]?.chunks?.add(AviChunkEntry(dataStart, chunkSize.toInt()))
            }
        }
    }

    private fun parseIdx1() {
        if (idx1Offset < 0L || idx1Size < 16L || videoChunks.isEmpty() || videoStreamNo < 0) return
        val flags = BooleanArray(videoChunks.size)
        var n = 0
        var off = 0L
        val block = 16 * 4096
        while (off + 16 <= idx1Size && n < flags.size) {
            val b = readBytes(idx1Offset + off, minOf(block.toLong(), idx1Size - off).toInt()) ?: break
            var p = 0
            while (p + 16 <= b.size && n < flags.size) {
                if (b[p].toInt() in '0'.code..'9'.code && b[p + 1].toInt() in '0'.code..'9'.code) {
                    val no = (b[p] - '0'.code.toByte()) * 10 + (b[p + 1] - '0'.code.toByte())
                    val s2 = b[p + 2].toInt().toChar().lowercaseChar()
                    val s3 = b[p + 3].toInt().toChar().lowercaseChar()
                    if (no == videoStreamNo && s2 == 'd' && (s3 == 'c' || s3 == 'b')) {
                        flags[n++] = (le32(b, p + 4) and 0x10) != 0
                    }
                }
                p += 16
            }
            if (b.size < 16) break
            off += b.size - (b.size % 16)
        }
        if (n == flags.size && flags.any { it }) keyFlags = flags
    }

    fun isKeyFrame(index: Int): Boolean {
        keyFlags?.let { return it.getOrElse(index) { false } }
        val e = videoChunks.getOrNull(index) ?: return false
        if (e.size == 0) return false
        val b = readBytes(e.offset, minOf(e.size, 8192)) ?: return false
        return when (videoMimeType) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> {
                var key = false
                var nonKey = false
                forEachStartCode(b) { p ->
                    when (b[p].toInt() and 0x1F) {
                        5, 7 -> key = true
                        1 -> nonKey = true
                    }
                }
                key || !nonKey && b.size >= 4 && (b[4].toInt() and 0x1F).let { it == 5 || it == 7 }
            }
            MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                var key = false
                forEachStartCode(b) { p -> if (((b[p].toInt() shr 1) and 0x3F) in 16..34) key = true }
                key
            }
            MediaFormat.MIMETYPE_VIDEO_MPEG4 -> {
                var key = false
                var found = false
                forEachStartCode(b) { p ->
                    val code = b[p].toInt() and 0xFF
                    if (code in 0x20..0x2F || code == 0xB0) key = true
                    if (code == 0xB6 && !found && p + 1 < b.size) {
                        found = true
                        if ((b[p + 1].toInt() and 0xC0) == 0) key = true
                    }
                }
                key
            }
            MediaFormat.MIMETYPE_VIDEO_VP8 -> (b[0].toInt() and 1) == 0
            else -> true
        }
    }

    private inline fun forEachStartCode(b: ByteArray, visit: (Int) -> Unit) {
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                visit(i + 3)
                i += 3
            } else {
                i++
            }
        }
    }

    fun keyFrameAtOrBefore(index: Int): Int {
        var i = index.coerceIn(0, (videoChunks.size - 1).coerceAtLeast(0))
        var guard = 0
        while (i > 0 && !isKeyFrame(i) && guard < 3000) {
            i--
            guard++
        }
        return i
    }

    fun loadSubtitles(): List<EmbeddedSubtitle> {
        val out = ArrayList<EmbeddedSubtitle>()
        for ((n, ts) in textStreams.withIndex()) {
            for (e in ts.chunks) {
                val b = readChunkBytes(e) ?: continue
                val parsed = parseGab2(b) ?: continue
                val track = SubtitleTrackParser.parse(parsed.second, parsed.first.takeIf { it.contains('.') }) ?: continue
                val label = ts.name?.takeIf { it.isNotBlank() } ?: parsed.first.takeIf { it.isNotBlank() } ?: "#${n + 1}"
                out.add(EmbeddedSubtitle(label, null, track))
                break
            }
        }
        return out
    }

    private fun parseGab2(b: ByteArray): Pair<String, ByteArray>? {
        if (b.size < 17 || String(b, 0, 4, StandardCharsets.US_ASCII) != "GAB2" || b[4].toInt() != 0 || le16(b, 5) != 2) return null
        val nameSize = le32(b, 7)
        if (nameSize < 0 || 11 + nameSize + 6 > b.size) return null
        val name = String(b, 11, nameSize, Charsets.UTF_16LE).trimEnd('\u0000')
        var p = 11 + nameSize
        p += 2
        val dataSize = le32(b, p)
        p += 4
        if (dataSize <= 0 || p + dataSize > b.size) return null
        return name to b.copyOfRange(p, p + dataSize)
    }

    fun readChunkBytes(entry: AviChunkEntry): ByteArray? = readBytes(entry.offset, entry.size)

    private fun lastNonEmptyAtOrBefore(index: Int): Int {
        var i = index.coerceIn(0, videoChunks.lastIndex)
        while (i > 0 && videoChunks[i].size == 0) i--
        return i
    }

    fun decodeVideoFrameAt(frameIndex: Int): Bitmap? {
        if (videoChunks.isEmpty()) return null
        val idx = lastNonEmptyAtOrBefore(frameIndex)
        val bytes = readChunkBytes(videoChunks[idx]) ?: return null

        val mime = videoMimeType
        if (mime == null || mime == "video/mjpeg") {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (decoded != null) return decoded
        }

        val pixelCount = width * height
        if ((mime == null || videoFourCc.isEmpty() || videoFourCc == "DIB" || videoFourCc == "RGB") &&
            width in 16..4096 && height in 16..4096 && bytes.size >= pixelCount * 3
        ) {
            val pixels = IntArray(pixelCount)
            val bpp = if (bytes.size >= pixelCount * 4) 4 else 3
            val stride = ((width * bpp + 3) / 4) * 4
            for (y in 0 until height) {
                val srcRow = (height - 1 - y) * stride
                val dstRow = y * width
                for (x in 0 until width) {
                    val p = srcRow + x * bpp
                    if (p + 2 >= bytes.size) break
                    val b = bytes[p].toInt() and 0xFF
                    val g = bytes[p + 1].toInt() and 0xFF
                    val r = bytes[p + 2].toInt() and 0xFF
                    pixels[dstRow + x] = Color.rgb(r, g, b)
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        if (mime != null && mime != "video/mjpeg") return decodeCompressedFrameViaMediaCodec(mime, frameIndex)
        return null
    }

    fun videoFormat(): MediaFormat? {
        val mime = videoMimeType ?: return null
        if (mime == "video/mjpeg") return null
        val format = MediaFormat.createVideoFormat(mime, width, height)
        val maxChunk = videoChunks.maxOfOrNull { it.size } ?: 0
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (maxChunk + 4096).coerceAtLeast(64 * 1024))
        val extra = videoCodecExtraData
        val avcc = avcConfig()
        if (avcc != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(avcc.first))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(avcc.second))
        } else if (extra != null && extra.isNotEmpty() && mime != MediaFormat.MIMETYPE_VIDEO_AVC && !(mime == MediaFormat.MIMETYPE_VIDEO_HEVC && extra[0].toInt() == 1)) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(extra))
        } else if (extra != null && extra.size > 4 && extra[0].toInt() == 0 && extra[1].toInt() == 0) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(extra))
        } else {
            inBandConfig?.let { cfg -> cfg.forEachIndexed { i, b -> format.setByteBuffer("csd-$i", ByteBuffer.wrap(b)) } }
        }
        return format
    }

    private val inBandConfig: List<ByteArray>? by lazy {
        val mime = videoMimeType
        if (mime != MediaFormat.MIMETYPE_VIDEO_AVC && mime != MediaFormat.MIMETYPE_VIDEO_HEVC && mime != MediaFormat.MIMETYPE_VIDEO_MPEG4) return@lazy null
        for (k in 0 until minOf(videoChunks.size, 30)) {
            val e = videoChunks[k]
            if (e.size == 0) continue
            val b = toAnnexB(readChunkBytes(e) ?: continue)
            val starts = ArrayList<Int>()
            forEachStartCode(b) { starts.add(it) }
            if (starts.isEmpty()) continue
            fun unit(i: Int): ByteArray {
                val from = starts[i]
                var to = if (i + 1 < starts.size) starts[i + 1] - 3 else b.size
                while (to > from && b[to - 1].toInt() == 0) to--
                return byteArrayOf(0, 0, 0, 1) + b.copyOfRange(from, to)
            }
            when (mime) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> {
                    val sps = starts.indices.firstOrNull { (b[starts[it]].toInt() and 0x1F) == 7 } ?: continue
                    val pps = starts.indices.firstOrNull { (b[starts[it]].toInt() and 0x1F) == 8 } ?: continue
                    return@lazy listOf(unit(sps), unit(pps))
                }
                MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                    val parts = starts.indices.filter { ((b[starts[it]].toInt() shr 1) and 0x3F) in 32..34 }
                    if (parts.size < 3) continue
                    return@lazy listOf(parts.fold(ByteArray(0)) { acc, i -> acc + unit(i) })
                }
                else -> {
                    val vop = starts.firstOrNull { (b[it].toInt() and 0xFF) == 0xB6 } ?: continue
                    if (starts.none { (b[it].toInt() and 0xFF) in 0x20..0x2F }) continue
                    val from = starts.first() - 3
                    return@lazy listOf(b.copyOfRange(from.coerceAtLeast(0), vop - 3))
                }
            }
        }
        null
    }

    val nalLengthSize: Int by lazy {
        val e = videoCodecExtraData
        if (videoMimeType == MediaFormat.MIMETYPE_VIDEO_AVC && e != null && e.size >= 7 && e[0].toInt() == 1) (e[4].toInt() and 3) + 1 else 0
    }

    private fun avcConfig(): Pair<ByteArray, ByteArray>? {
        if (nalLengthSize == 0) return null
        val e = videoCodecExtraData ?: return null
        return try {
            val sps = java.io.ByteArrayOutputStream()
            val pps = java.io.ByteArrayOutputStream()
            var p = 5
            val nSps = e[p++].toInt() and 0x1F
            repeat(nSps) {
                val len = ((e[p].toInt() and 0xFF) shl 8) or (e[p + 1].toInt() and 0xFF)
                p += 2
                sps.write(byteArrayOf(0, 0, 0, 1))
                sps.write(e, p, len)
                p += len
            }
            val nPps = e[p++].toInt() and 0xFF
            repeat(nPps) {
                val len = ((e[p].toInt() and 0xFF) shl 8) or (e[p + 1].toInt() and 0xFF)
                p += 2
                pps.write(byteArrayOf(0, 0, 0, 1))
                pps.write(e, p, len)
                p += len
            }
            sps.toByteArray() to pps.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    fun toAnnexB(data: ByteArray): ByteArray {
        val n = nalLengthSize
        if (n == 0 || data.size < n) return data
        if (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 && (data[2].toInt() == 1 || data[2].toInt() == 0 && data[3].toInt() == 1)) return data
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var p = 0
        while (p + n <= data.size) {
            var len = 0
            for (k in 0 until n) len = (len shl 8) or (data[p + k].toInt() and 0xFF)
            p += n
            if (len <= 0 || p + len > data.size) break
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(data, p, len)
            p += len
        }
        return out.toByteArray()
    }

    private fun decodeCompressedFrameViaMediaCodec(mime: String, targetFrameIndex: Int): Bitmap? {
        var codec: MediaCodec? = null
        return try {
            val format = videoFormat() ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val target = targetFrameIndex.coerceIn(0, videoChunks.lastIndex)
            val targetUs = target * frameDurationUs
            var idx = keyFrameAtOrBefore(target)
            var inputDone = false
            var result: Bitmap? = null
            var spins = 0
            while (result == null && spins < 2000) {
                spins++
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        var queued = false
                        while (idx < videoChunks.size && !queued) {
                            val e = videoChunks[idx]
                            val pts = idx * frameDurationUs
                            idx++
                            if (e.size == 0) continue
                            val bytes = toAnnexB(readChunkBytes(e) ?: continue)
                            val inBuf = codec.getInputBuffer(inIdx) ?: break
                            if (bytes.size > inBuf.capacity()) continue
                            inBuf.clear()
                            inBuf.put(bytes)
                            codec.queueInputBuffer(inIdx, 0, bytes.size, pts, 0)
                            queued = true
                        }
                        if (!queued) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (info.presentationTimeUs >= targetUs - 1000L || eos) {
                        val image = try { codec.getOutputImage(outIdx) } catch (_: Throwable) { null }
                        result = if (image != null) {
                            try { yuv420ImageToBitmap(image) } finally { image.close() }
                        } else null
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos) break
                }
            }
            result
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
        val yPixelStride = planes[0].pixelStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val yRow = y * yRowStride
            val uvRow = (y shr 1) * uvRowStride
            for (x in 0 until w) {
                val yVal = (yBuf.get(yRow + x * yPixelStride).toInt() and 0xFF)
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

internal object AdpcmDecoder {
    private val IMA_INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val IMA_STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060,
        1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
        7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )
    private val MS_ADAPT = intArrayOf(230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230, 230)
    private val MS_COEF1 = intArrayOf(256, 512, 0, 192, 240, 460, 392)
    private val MS_COEF2 = intArrayOf(0, -256, 0, 64, 0, -208, -232)

    fun samplesPerBlock(tag: Int, blockAlign: Int, channels: Int, extra: ByteArray?): Int {
        if (extra != null && extra.size >= 2) {
            val v = (extra[0].toInt() and 0xFF) or ((extra[1].toInt() and 0xFF) shl 8)
            if (v > 0) return v
        }
        return if (tag == 0x11) (blockAlign - 4 * channels) * 8 / (4 * channels) + 1 else (blockAlign - 7 * channels) * 8 / (4 * channels) + 2
    }

    private fun s16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()

    fun decode(tag: Int, data: ByteArray, off: Int, blockAlign: Int, channels: Int, extra: ByteArray?, out: java.io.ByteArrayOutputStream) {
        if (blockAlign <= 0) return
        val spb = samplesPerBlock(tag, blockAlign, channels, extra)
        if (spb <= 0) return
        val pcm = ShortArray(spb * channels)
        var p = off
        while (p + blockAlign <= data.size) {
            val n = if (tag == 0x11) imaBlock(data, p, blockAlign, channels, spb, pcm) else msBlock(data, p, channels, spb, extra, pcm)
            for (i in 0 until n * channels) {
                val v = pcm[i].toInt()
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
            p += blockAlign
        }
    }

    private fun imaBlock(b: ByteArray, start: Int, blockAlign: Int, ch: Int, spb: Int, pcm: ShortArray): Int {
        val pred = IntArray(ch)
        val idx = IntArray(ch)
        for (c in 0 until ch) {
            pred[c] = s16(b, start + c * 4)
            idx[c] = (b[start + c * 4 + 2].toInt() and 0xFF).coerceIn(0, 88)
            pcm[c] = pred[c].toShort()
        }
        var p = start + 4 * ch
        val end = start + blockAlign
        var sampleIdx = 1
        while (p < end && sampleIdx < spb) {
            for (c in 0 until ch) {
                for (k in 0 until 4) {
                    if (p + k >= end) break
                    val byte = b[p + k].toInt() and 0xFF
                    for (half in 0..1) {
                        val nib = if (half == 0) byte and 0x0F else byte shr 4
                        val step = IMA_STEP[idx[c]]
                        var diff = step shr 3
                        if (nib and 4 != 0) diff += step
                        if (nib and 2 != 0) diff += step shr 1
                        if (nib and 1 != 0) diff += step shr 2
                        pred[c] = (if (nib and 8 != 0) pred[c] - diff else pred[c] + diff).coerceIn(-32768, 32767)
                        idx[c] = (idx[c] + IMA_INDEX[nib]).coerceIn(0, 88)
                        val s = sampleIdx + k * 2 + half
                        if (s < spb) pcm[s * ch + c] = pred[c].toShort()
                    }
                }
                p += 4
            }
            sampleIdx += 8
        }
        return spb
    }

    private fun msBlock(b: ByteArray, start: Int, ch: Int, spb: Int, extra: ByteArray?, pcm: ShortArray): Int {
        val c1 = IntArray(ch)
        val c2 = IntArray(ch)
        val delta = IntArray(ch)
        val s1 = IntArray(ch)
        val s2 = IntArray(ch)
        var coef1 = MS_COEF1
        var coef2 = MS_COEF2
        if (extra != null && extra.size >= 4) {
            val n = (extra[2].toInt() and 0xFF) or ((extra[3].toInt() and 0xFF) shl 8)
            if (n in 1..256 && extra.size >= 4 + n * 4) {
                coef1 = IntArray(n) { s16(extra, 4 + it * 4) }
                coef2 = IntArray(n) { s16(extra, 6 + it * 4) }
            }
        }
        var p = start
        for (c in 0 until ch) {
            val pi = (b[p + c].toInt() and 0xFF).coerceIn(0, coef1.size - 1)
            c1[c] = coef1[pi]
            c2[c] = coef2[pi]
        }
        p += ch
        for (c in 0 until ch) delta[c] = s16(b, p + c * 2)
        p += 2 * ch
        for (c in 0 until ch) s1[c] = s16(b, p + c * 2)
        p += 2 * ch
        for (c in 0 until ch) s2[c] = s16(b, p + c * 2)
        p += 2 * ch
        for (c in 0 until ch) {
            pcm[c] = s2[c].toShort()
            if (spb > 1) pcm[ch + c] = s1[c].toShort()
        }
        var outIdx = 2 * ch
        var nibbleHigh = true
        var c = 0
        while (outIdx < spb * ch && p < b.size) {
            val byte = b[p].toInt() and 0xFF
            val nib = if (nibbleHigh) byte shr 4 else byte and 0x0F
            if (!nibbleHigh) p++
            nibbleHigh = !nibbleHigh
            val signed = if (nib >= 8) nib - 16 else nib
            var pred = (s1[c] * c1[c] + s2[c] * c2[c]) shr 8
            pred += signed * delta[c]
            pred = pred.coerceIn(-32768, 32767)
            s2[c] = s1[c]
            s1[c] = pred
            delta[c] = ((MS_ADAPT[nib] * delta[c]) shr 8).coerceAtLeast(16)
            pcm[outIdx++] = pred.toShort()
            c = (c + 1) % ch
        }
        return spb
    }
}

class SoftwareAudioTrack(val id: Int, val label: String?, val language: String?)

private class SwClock(startUs: Long, private val speed: Float) {
    private var anchorUs = startUs
    private var anchorNs = System.nanoTime()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackRate = 0
    @Volatile private var trackBaseUs = 0L
    @Volatile private var lastTrackUs = Long.MIN_VALUE
    @Volatile var waitingForAudio = false

    @Synchronized
    fun attachAudio(t: AudioTrack, rate: Int, baseUs: Long) {
        track = t
        trackRate = rate
        trackBaseUs = baseUs
        lastTrackUs = Long.MIN_VALUE
        waitingForAudio = false
    }

    @Synchronized
    fun detachAudio(t: AudioTrack?) {
        if (t != null && track !== t) return
        val now = nowUs()
        track = null
        waitingForAudio = false
        anchorUs = now
        anchorNs = System.nanoTime()
    }

    fun nowUs(): Long {
        val t = track
        if (t != null && trackRate > 0) {
            val frames = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Throwable) { 0L }
            val v = maxOf(trackBaseUs + frames * 1_000_000L / trackRate, lastTrackUs)
            lastTrackUs = v
            return v
        }
        if (waitingForAudio) return anchorUs
        return anchorUs + ((System.nanoTime() - anchorNs) / 1000.0 * speed).toLong()
    }
}

private class SwPacket {
    var size = 0
    var ptsUs = 0L
}

private interface SwPacketSource {
    fun next(buf: ByteBuffer, out: SwPacket): Boolean
    fun outputPts(index: Int, decoderPtsUs: Long): Long = decoderPtsUs
    fun close() {}
}

private class FdMediaDataSource(private val source: ZeroCacheReadableSource) : android.media.MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= source.size) return -1
        if (size <= 0) return 0
        val n = source.readAt(position, ByteBuffer.wrap(buffer, offset, size))
        return if (n <= 0) -1 else n
    }

    override fun getSize(): Long = source.size

    override fun close() {}
}

private class ExtractorPacketSource(source: ZeroCacheReadableSource, track: Int, seekUs: Long) : SwPacketSource {
    private val ex = MediaExtractor()

    init {
        try {
            ex.setDataSource(FdMediaDataSource(source))
            ex.selectTrack(track)
            if (seekUs > 0L) ex.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        } catch (t: Throwable) {
            try { ex.release() } catch (_: Throwable) {}
            throw t
        }
    }

    override fun close() {
        try { ex.release() } catch (_: Throwable) {}
    }

    override fun next(buf: ByteBuffer, out: SwPacket): Boolean {
        while (true) {
            if (ex.sampleTrackIndex < 0) return false
            val n = try { ex.readSampleData(buf, 0) } catch (_: IllegalArgumentException) { -2 }
            val pts = ex.sampleTime
            ex.advance()
            if (n == -2) continue
            if (n < 0) return false
            out.size = n
            out.ptsUs = pts
            return true
        }
    }
}

private class AviVideoPacketSource(private val demux: AviDemuxer, startIndex: Int) : SwPacketSource {
    private var i = startIndex
    private val slots = ArrayList<Int>()

    override fun outputPts(index: Int, decoderPtsUs: Long): Long {
        val slot = synchronized(slots) { slots.getOrNull(index) } ?: return decoderPtsUs
        return slot * demux.frameDurationUs
    }

    override fun next(buf: ByteBuffer, out: SwPacket): Boolean {
        while (i < demux.videoChunks.size) {
            val e = demux.videoChunks[i]
            val pts = i * demux.frameDurationUs
            i++
            if (e.size == 0) continue
            val bytes = demux.toAnnexB(demux.readChunkBytes(e) ?: continue)
            if (bytes.size > buf.capacity()) continue
            buf.clear()
            buf.put(bytes)
            out.size = bytes.size
            out.ptsUs = pts
            synchronized(slots) { slots.add(i - 1) }
            return true
        }
        return false
    }
}

private class AviAudioPacketSource(
    private val demux: AviDemuxer,
    private val st: AviDemuxer.AudioStream,
    startIndex: Int
) : SwPacketSource {
    private var i = startIndex
    private val mp3 = st.mimeType == MediaFormat.MIMETYPE_AUDIO_MPEG
    private var pending = ByteArray(0)
    private var pendingPtsUs = st.chunkStartUs(startIndex)

    override fun next(buf: ByteBuffer, out: SwPacket): Boolean {
        if (!mp3) {
            while (i < st.chunks.size) {
                val e = st.chunks[i]
                val pts = st.chunkStartUs(i)
                i++
                val bytes = demux.readChunkBytes(e) ?: continue
                if (bytes.size > buf.capacity()) continue
                buf.clear()
                buf.put(bytes)
                out.size = bytes.size
                out.ptsUs = pts
                return true
            }
            return false
        }
        while (true) {
            val frame = takeMp3Frame()
            if (frame != null) {
                if (frame.first.size > buf.capacity()) continue
                buf.clear()
                buf.put(frame.first)
                out.size = frame.first.size
                out.ptsUs = pendingPtsUs
                pendingPtsUs += frame.second
                return true
            }
            if (i >= st.chunks.size) return false
            val bytes = demux.readChunkBytes(st.chunks[i]) ?: ByteArray(0)
            if (pending.isEmpty()) pendingPtsUs = maxOf(pendingPtsUs, st.chunkStartUs(i))
            i++
            pending = if (pending.isEmpty()) bytes else pending + bytes
        }
    }

    private fun mp3FrameInfo(b: ByteArray, p: Int): Pair<Int, Long>? {
        if (p + 4 > b.size) return null
        val h = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
        if ((h ushr 21) and 0x7FF != 0x7FF) return null
        val ver = (h ushr 19) and 3
        val layer = (h ushr 17) and 3
        val brIdx = (h ushr 12) and 0xF
        val srIdx = (h ushr 10) and 3
        val pad = (h ushr 9) and 1
        if (ver == 1 || layer == 0 || brIdx == 0 || brIdx == 15 || srIdx == 3) return null
        val v1 = ver == 3
        val l = 4 - layer
        val tableV1 = arrayOf(
            intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
            intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        )
        val tableV2 = arrayOf(
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        )
        val br = (if (v1) tableV1 else tableV2)[l - 1][brIdx] * 1000
        val baseSr = intArrayOf(44100, 48000, 32000)[srIdx]
        val sr = when (ver) { 3 -> baseSr; 2 -> baseSr / 2; else -> baseSr / 4 }
        val samples = when {
            l == 1 -> 384
            l == 3 && !v1 -> 576
            else -> 1152
        }
        val len = when (l) {
            1 -> (12 * br / sr + pad) * 4
            else -> samples / 8 * br / sr + pad
        }
        if (len < 4) return null
        return len to samples * 1_000_000L / sr
    }

    private fun takeMp3Frame(): Pair<ByteArray, Long>? {
        var p = 0
        while (p + 4 <= pending.size) {
            val info = mp3FrameInfo(pending, p)
            if (info != null) {
                val end = p + info.first
                if (end > pending.size) {
                    pending = pending.copyOfRange(p, pending.size)
                    return null
                }
                val nextOk = end + 4 > pending.size || mp3FrameInfo(pending, end) != null
                if (nextOk) {
                    val frame = pending.copyOfRange(p, end)
                    pending = pending.copyOfRange(end, pending.size)
                    return frame to info.second
                }
            }
            p++
        }
        pending = if (pending.size > 3) pending.copyOfRange(pending.size - 3, pending.size) else pending
        return null
    }
}

internal class HybridMediaPlayer : MediaPlayer() {
    @Volatile var isSoftwareAviMode = false
        private set

    private var aviDemuxer: AviDemuxer? = null
    private var dataSource: ZeroCacheReadableSource? = null
    private var exVideoTrack = -1
    private var exVideoFormat: MediaFormat? = null
    private val exAudioFormats = HashMap<Int, MediaFormat>()
    private var exDurationMs = 0
    private val swAudioTracks = ArrayList<SoftwareAudioTrack>()
    @Volatile private var selectedAudioId = -1
    private var swVideoWidth = 0
    private var swVideoHeight = 0
    @Volatile var previewFallbackBitmap: Bitmap? = null
    @Volatile private var attachedSurface: Surface? = null
    @Volatile private var swPlaying = false
    @Volatile private var swCurrentPositionMs = 0
    @Volatile var swSpeed = 1f
        private set
    @Volatile private var swVolume = 1f
    @Volatile private var activeAudioTrack: AudioTrack? = null
    @Volatile private var clock: SwClock? = null
    @Volatile private var swCompletionListener: OnCompletionListener? = null
    @Volatile private var audioJob: Job? = null
    @Volatile private var videoJob: Job? = null
    @Volatile private var watchJob: Job? = null
    @Volatile private var previewJob: Job? = null
    @Volatile private var generation = 0
    private val lock = Any()

    private val _embeddedSubtitles = MutableStateFlow<List<EmbeddedSubtitle>>(emptyList())
    val embeddedSubtitles: StateFlow<List<EmbeddedSubtitle>> = _embeddedSubtitles.asStateFlow()

    @Volatile var audioQualitySpec: AudioQualitySpec = AudioQualitySpec()
        private set
    @Volatile var audioManagerRef: AudioManager? = null
    private val _activeAudioOutputConfig = MutableStateFlow(0 to 0)
    val activeAudioOutputConfig: StateFlow<Pair<Int, Int>> = _activeAudioOutputConfig.asStateFlow()

    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun updateAudioQualitySpec(spec: AudioQualitySpec, audioManager: AudioManager?) {
        synchronized(lock) {
            if (audioManager != null) audioManagerRef = audioManager
            if (spec == audioQualitySpec) return
            audioQualitySpec = spec
            if (isSoftwareAviMode && swPlaying) {
                restartAll()
            }
        }
    }

    fun pauseNativeSuperQuietly() {
        try {
            if (!isSoftwareAviMode && super.isPlaying()) {
                super.pause()
            }
        } catch (_: Throwable) {}
    }

    fun setSoftwareInitialState(positionMs: Int, speed: Float) {
        synchronized(lock) {
            swCurrentPositionMs = positionMs.coerceIn(0, swDurationMs())
            swSpeed = speed.coerceIn(0.25f, 4f)
        }
    }

    fun softwareAudioTracks(): List<SoftwareAudioTrack> = synchronized(lock) { swAudioTracks.toList() }

    fun selectedSoftwareAudio(): Int = selectedAudioId

    fun selectSoftwareAudio(id: Int) {
        synchronized(lock) {
            if (swAudioTracks.none { it.id == id } || id == selectedAudioId) return
            selectedAudioId = id
            if (swPlaying) restartAll()
        }
    }

    fun loadEmbeddedSubtitles(fd: ZeroCacheReadableSource) {
        playbackScope.launch {
            val list = try {
                val reader = MatroskaSubtitleExtractor.Reader { off, dst, dstOff, len -> fd.readAt(off, ByteBuffer.wrap(dst, dstOff, len)) }
                val head = ByteArray(4)
                if (reader.read(0L, head, 0, 4) == 4 && MatroskaSubtitleExtractor.isMatroska(head)) {
                    MatroskaSubtitleExtractor.read(reader, fd.size)
                } else {
                    extractTextTracks(fd)
                }
            } catch (_: Throwable) {
                emptyList()
            }
            if (list.isNotEmpty()) _embeddedSubtitles.value = list
        }
    }

    private fun extractTextTracks(src: ZeroCacheReadableSource): List<EmbeddedSubtitle> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(FdMediaDataSource(src))
            val tracks = ArrayList<Pair<Int, MediaFormat>>()
            var durUs = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) durUs = maxOf(durUs, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("text/") || mime.contains("subrip") || mime.contains("x-ssa") || mime.contains("x-ass")) tracks.add(i to f)
            }
            if (tracks.isEmpty()) return emptyList()
            tracks.forEach { ex.selectTrack(it.first) }
            val samples = HashMap<Int, ArrayList<Pair<Long, ByteArray>>>()
            val buf = ByteBuffer.allocate(512 * 1024)
            var count = 0
            while (count < 200_000) {
                val ti = ex.sampleTrackIndex
                if (ti < 0) break
                buf.clear()
                val n = try { ex.readSampleData(buf, 0) } catch (_: Throwable) { -1 }
                val pts = ex.sampleTime
                if (n >= 0) {
                    val bytes = ByteArray(n)
                    buf.position(0)
                    buf.get(bytes, 0, n)
                    samples.getOrPut(ti) { ArrayList() }.add(pts to bytes)
                    count++
                }
                if (!ex.advance()) break
            }
            val out = ArrayList<EmbeddedSubtitle>()
            for ((n, t) in tracks.withIndex()) {
                val list = samples[t.first] ?: continue
                val mime = t.second.getString(MediaFormat.KEY_MIME) ?: continue
                val track = SubtitleTrackParser.fromSamples(mime, list, durUs / 1000L) ?: continue
                val lang = t.second.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotBlank() && it != "und" }
                out.add(EmbeddedSubtitle("#${n + 1}", lang, track))
            }
            return out
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    override fun setPlaybackParams(params: android.media.PlaybackParams) {
        if (isSoftwareAviMode) {
            val sp = try { params.speed } catch (_: Throwable) { 1f }
            synchronized(lock) {
                val target = sp.coerceIn(0.25f, 4f)
                if (target == swSpeed) return
                if (swPlaying) {
                    swCurrentPositionMs = livePositionMs()
                    swSpeed = target
                    startSoftware()
                } else {
                    swSpeed = target
                }
            }
        } else {
            super.setPlaybackParams(params)
        }
    }

    override fun getPlaybackParams(): android.media.PlaybackParams {
        return if (isSoftwareAviMode) android.media.PlaybackParams().setSpeed(swSpeed) else super.getPlaybackParams()
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        swVolume = maxOf(leftVolume, rightVolume).coerceIn(0f, 1f)
        activeAudioTrack?.let { t -> try { t.setVolume(swVolume) } catch (_: Throwable) {} }
        if (!isSoftwareAviMode) try { super.setVolume(leftVolume, rightVolume) } catch (_: Throwable) {}
    }

    private fun hasDecoder(format: MediaFormat): Boolean {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            val byFormat = try { list.findDecoderForFormat(format) } catch (_: Throwable) { null }
            byFormat != null || list.codecInfos.any { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        } catch (_: Throwable) {
            false
        }
    }

    private fun createDecoder(format: MediaFormat, softwareOnly: Boolean = false): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        val name = if (softwareOnly) {
            list.codecInfos.firstOrNull { info ->
                !info.isEncoder && info.isSoftwareOnly && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }?.name ?: throw IllegalStateException("no software decoder")
        } else {
            try { list.findDecoderForFormat(format) } catch (_: Throwable) { null }
        }
        return if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
    }

    private fun aviAudioFormat(st: AviDemuxer.AudioStream): MediaFormat? {
        val mime = st.mimeType ?: return null
        if (st.isRawPcm || st.isAdpcm) return null
        val f = MediaFormat.createAudioFormat(mime, st.sampleRate.coerceAtLeast(8000), st.channels.coerceIn(1, 8))
        val maxChunk = st.chunks.maxOfOrNull { it.size } ?: 0
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (maxChunk + 4096).coerceAtLeast(16 * 1024))
        val extra = st.extra
        when {
            mime == MediaFormat.MIMETYPE_AUDIO_AAC && (st.formatTag == 0x1600 || st.formatTag == 0x1601) -> f.setInteger(MediaFormat.KEY_IS_ADTS, 1)
            mime == MediaFormat.MIMETYPE_AUDIO_AAC -> {
                val asc = if (extra != null && extra.size >= 2 && st.formatTag != 0xFFFE) extra else {
                    val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
                    val fi = rates.indexOfFirst { it == st.sampleRate }.let { if (it < 0) 4 else it }
                    val ch = st.channels.coerceIn(1, 7)
                    byteArrayOf(((2 shl 3) or (fi shr 1)).toByte(), (((fi and 1) shl 7) or (ch shl 3)).toByte())
                }
                f.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
            }
            mime == MediaFormat.MIMETYPE_AUDIO_MPEG -> {}
            extra != null && extra.isNotEmpty() -> f.setByteBuffer("csd-0", ByteBuffer.wrap(extra))
        }
        return f
    }

    private fun aviAudioPlayable(st: AviDemuxer.AudioStream): Boolean {
        if (st.sampleRate <= 0 || st.chunks.isEmpty()) return false
        if (st.isRawPcm) return st.formatTag == 3 && st.bitsPerSample in setOf(32, 64) || st.formatTag == 1 && st.bitsPerSample in setOf(8, 16, 24, 32)
        if (st.isAdpcm) return st.blockAlign > 0 && st.channels in 1..2
        val f = aviAudioFormat(st) ?: return false
        return hasDecoder(f)
    }

    fun activateInternalAviMode(demuxer: AviDemuxer): Boolean {
        val vf = demuxer.videoFormat()
        val hasHardwareVideo = vf != null && demuxer.videoChunks.isNotEmpty() && hasDecoder(vf)
        val hasSoftwareVideo = !hasHardwareVideo && demuxer.videoChunks.isNotEmpty() && demuxer.decodeVideoFrameAt(0)?.also {
            if (previewFallbackBitmap == null) previewFallbackBitmap = it else it.recycle()
        } != null
        val tracks = ArrayList<SoftwareAudioTrack>()
        demuxer.audioStreams.forEachIndexed { i, st ->
            if (aviAudioPlayable(st)) tracks.add(SoftwareAudioTrack(i, st.name, null))
        }
        if (!hasHardwareVideo && !hasSoftwareVideo && tracks.isEmpty()) return false
        synchronized(lock) {
            aviDemuxer = demuxer
            swAudioTracks.clear()
            swAudioTracks.addAll(tracks)
            selectedAudioId = tracks.firstOrNull()?.id ?: -1
            swVideoWidth = if (hasHardwareVideo || hasSoftwareVideo) demuxer.width else 0
            swVideoHeight = if (hasHardwareVideo || hasSoftwareVideo) demuxer.height else 0
            isSoftwareAviMode = true
        }
        if (demuxer.textStreams.isNotEmpty()) {
            playbackScope.launch {
                val subs = try { demuxer.loadSubtitles() } catch (_: Throwable) { emptyList() }
                if (subs.isNotEmpty()) _embeddedSubtitles.value = subs
            }
        }
        return true
    }

    val softwareHasVideo: Boolean get() = swVideoWidth > 0

    fun activateMediaExtractorCodecFallback(source: ZeroCacheReadableSource): Pair<Boolean, Int>? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(FdMediaDataSource(source))
            if (extractor.trackCount <= 0) return null
            var maxDurUs = 0L
            var videoIdx = -1
            var videoFmt: MediaFormat? = null
            val audio = ArrayList<SoftwareAudioTrack>()
            val audioFormats = HashMap<Int, MediaFormat>()
            var defaultAudio = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) maxDurUs = maxOf(maxDurUs, fmt.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/") && videoIdx < 0 && hasDecoder(fmt)) {
                    videoIdx = i
                    videoFmt = fmt
                } else if (mime.startsWith("audio/") && hasDecoder(fmt)) {
                    val lang = fmt.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotBlank() && it != "und" }
                    audio.add(SoftwareAudioTrack(i, null, lang))
                    audioFormats[i] = fmt
                    if (defaultAudio < 0 && fmt.containsKey(MediaFormat.KEY_IS_DEFAULT) && fmt.getInteger(MediaFormat.KEY_IS_DEFAULT) == 1) defaultAudio = i
                }
            }
            if (videoIdx < 0 && audio.isEmpty()) return null
            synchronized(lock) {
                dataSource = source
                exVideoTrack = videoIdx
                exVideoFormat = videoFmt
                exAudioFormats.clear()
                exAudioFormats.putAll(audioFormats)
                swAudioTracks.clear()
                swAudioTracks.addAll(audio)
                selectedAudioId = if (defaultAudio >= 0) defaultAudio else audio.firstOrNull()?.id ?: -1
                exDurationMs = (maxDurUs / 1000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                if (videoFmt != null) {
                    var w = if (videoFmt.containsKey(MediaFormat.KEY_WIDTH)) videoFmt.getInteger(MediaFormat.KEY_WIDTH) else 0
                    var h = if (videoFmt.containsKey(MediaFormat.KEY_HEIGHT)) videoFmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    val rot = if (videoFmt.containsKey(MediaFormat.KEY_ROTATION)) videoFmt.getInteger(MediaFormat.KEY_ROTATION) else 0
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    swVideoWidth = w
                    swVideoHeight = h
                }
                isSoftwareAviMode = true
            }
            (videoIdx >= 0) to exDurationMs
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
                val dstW = if (aspect > targetAspect) cw else ch * aspect
                val dstH = if (aspect > targetAspect) cw / aspect else ch
                val left = (cw - dstW) / 2f
                val top = (ch - dstH) / 2f
                canvas.drawBitmap(bmp, null, RectF(left, top, left + dstW, top + dstH), drawPaint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Throwable) {}
    }

    private val liveSurfaces = ArrayList<Surface>()

    fun detachSurface(surface: Surface) {
        val fallback = synchronized(liveSurfaces) {
            liveSurfaces.removeAll { it === surface || !it.isValid }
            liveSurfaces.lastOrNull()
        }
        if (attachedSurface === surface) setSurface(fallback)
    }

    override fun getVideoWidth(): Int = if (isSoftwareAviMode) swVideoWidth else super.getVideoWidth()

    override fun getVideoHeight(): Int = if (isSoftwareAviMode) swVideoHeight else super.getVideoHeight()

    override fun setSurface(surface: Surface?) {
        attachedSurface = surface
        if (surface != null) synchronized(liveSurfaces) {
            liveSurfaces.removeAll { it === surface || !it.isValid }
            liveSurfaces.add(surface)
        }
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
            return
        }
        synchronized(lock) {
            if (swPlaying) restartVideo() else if (surface != null && surface.isValid) schedulePreview()
        }
    }

    override fun setOnCompletionListener(listener: OnCompletionListener?) {
        swCompletionListener = listener
        try { super.setOnCompletionListener(listener) } catch (_: Throwable) {}
    }

    override fun isPlaying(): Boolean {
        return if (isSoftwareAviMode) swPlaying else try { super.isPlaying() } catch (_: Throwable) { false }
    }

    private fun swDurationMs(): Int = (aviDemuxer?.totalDurationMs ?: exDurationMs).coerceAtLeast(1)

    private fun swHasVideo(): Boolean = swVideoWidth > 0

    private fun livePositionMs(): Int {
        val c = clock
        return if (swPlaying && c != null) (c.nowUs() / 1000L).coerceIn(0L, swDurationMs().toLong()).toInt() else swCurrentPositionMs
    }

    override fun getDuration(): Int {
        return if (isSoftwareAviMode) swDurationMs() else try { super.getDuration() } catch (_: Throwable) { 1 }
    }

    override fun getCurrentPosition(): Int {
        return if (isSoftwareAviMode) livePositionMs() else try { super.getCurrentPosition() } catch (_: Throwable) { 0 }
    }

    override fun seekTo(msec: Int) {
        if (!isSoftwareAviMode) {
            try { super.seekTo(msec) } catch (_: Throwable) {}
            return
        }
        synchronized(lock) {
            swCurrentPositionMs = msec.coerceIn(0, swDurationMs())
            if (swPlaying) startSoftware() else schedulePreview()
        }
    }

    override fun start() {
        if (!isSoftwareAviMode) {
            super.start()
            return
        }
        synchronized(lock) {
            if (swPlaying) return
            if (swCurrentPositionMs >= swDurationMs() - 50) swCurrentPositionMs = 0
            startSoftware()
        }
    }

    override fun pause() {
        if (isSoftwareAviMode) stopSoftware() else super.pause()
    }

    override fun stop() {
        if (isSoftwareAviMode) stopSoftware() else super.stop()
    }

    override fun release() {
        stopSoftware()
        previewJob?.cancel()
        try { playbackScope.cancel() } catch (_: Throwable) {}
        aviDemuxer = null
        dataSource = null
        previewFallbackBitmap = null
        try { super.release() } catch (_: Throwable) {}
    }

    private fun haltAudioOutput() {
        activeAudioTrack?.let { t ->
            try {
                t.pause()
                t.flush()
            } catch (_: Throwable) {}
        }
    }

    private fun stopSoftware() {
        synchronized(lock) {
            if (swPlaying) swCurrentPositionMs = livePositionMs()
            swPlaying = false
            generation++
            clock = null
            haltAudioOutput()
            audioJob?.cancel()
            videoJob?.cancel()
            watchJob?.cancel()
        }
    }

    private fun restartAll() {
        swCurrentPositionMs = livePositionMs()
        startSoftware()
    }

    private fun startSoftware() {
        val gen = ++generation
        val startUs = swCurrentPositionMs * 1000L
        val c = SwClock(startUs, swSpeed)
        haltAudioOutput()
        val prevA = audioJob
        val prevV = videoJob
        val prevP = previewJob
        prevA?.cancel()
        prevV?.cancel()
        prevP?.cancel()
        watchJob?.cancel()
        val audioOk = selectedAudioId >= 0 && swAudioTracks.any { it.id == selectedAudioId }
        if (audioOk) c.waitingForAudio = true
        clock = c
        swPlaying = true
        audioJob = if (audioOk) playbackScope.launch {
            prevA?.join()
            runAudio(c, startUs)
        } else null
        videoJob = launchVideo(c, listOfNotNull(prevV, prevP))
        watchJob = playbackScope.launch {
            while (true) {
                val a = audioJob
                val v = videoJob
                a?.join()
                v?.join()
                if (a === audioJob && v === videoJob) break
            }
            if (audioJob == null && videoJob == null) waitClockEnd(c)
            synchronized(lock) {
                if (generation != gen || !swPlaying) return@launch
                swPlaying = false
                swCurrentPositionMs = swDurationMs()
                clock = null
            }
            mainHandler.post {
                if (generation == gen) try { swCompletionListener?.onCompletion(this@HybridMediaPlayer) } catch (_: Throwable) {}
            }
        }
    }

    private fun restartVideo() {
        val c = clock ?: return
        val prevV = videoJob
        prevV?.cancel()
        videoJob = launchVideo(c, listOfNotNull(prevV, previewJob))
    }

    private fun launchVideo(c: SwClock, waitFor: List<Job>): Job? {
        if (!swHasVideo()) return null
        val surface = attachedSurface?.takeIf { it.isValid }
        return playbackScope.launch {
            waitFor.forEach { it.join() }
            if (surface == null || !surface.isValid) {
                waitClockEnd(c)
                return@launch
            }
            try {
                runVideo(c, surface)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                waitClockEnd(c)
            }
        }
    }

    private suspend fun waitClockEnd(c: SwClock) {
        val endUs = swDurationMs() * 1000L
        while (currentCoroutineContext().isActive) {
            val left = endUs - c.nowUs()
            if (left <= 0L) break
            delay((left / 1000L / swSpeed).toLong().coerceIn(5L, 200L))
        }
    }

    private fun schedulePreview() {
        if (!swHasVideo()) return
        val surface = attachedSurface?.takeIf { it.isValid } ?: return
        val prev = previewJob
        prev?.cancel()
        val prevV = videoJob
        val posUs = swCurrentPositionMs * 1000L
        previewJob = playbackScope.launch {
            prev?.join()
            prevV?.join()
            delay(40)
            try {
                renderPreview(surface, posUs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {}
        }
    }

    private suspend fun renderPreview(surface: Surface, posUs: Long) {
        val demux = aviDemuxer
        if (demux != null) {
            val vf = demux.videoFormat()
            if (vf != null && hasDecoder(vf)) {
                val target = (posUs / demux.frameDurationUs).toInt().coerceIn(0, demux.videoChunks.lastIndex)
                val key = demux.keyFrameAtOrBefore(target)
                if (runVideoDecoder(vf, { AviVideoPacketSource(demux, key) }, surface, target * demux.frameDurationUs, null)) return
            }
            val idx = (posUs / demux.frameDurationUs).toInt().coerceIn(0, demux.videoChunks.lastIndex)
            val bmp = demux.decodeVideoFrameAt(idx) ?: previewFallbackBitmap
            if (bmp != null && !bmp.isRecycled) {
                drawBitmapCenteredOnSurface(surface, bmp)
                if (bmp !== previewFallbackBitmap) bmp.recycle()
            }
            return
        }
        val src = dataSource ?: return
        val fmt = exVideoFormat ?: return
        runVideoDecoder(fmt, { ExtractorPacketSource(src, exVideoTrack, posUs) }, surface, posUs, null)
    }

    private suspend fun runVideo(c: SwClock, surface: Surface) {
        val startUs = c.nowUs()
        val demux = aviDemuxer
        if (demux != null) {
            val vf = demux.videoFormat()
            if (vf != null && hasDecoder(vf)) {
                val target = (startUs / demux.frameDurationUs).toInt().coerceIn(0, demux.videoChunks.lastIndex)
                val key = demux.keyFrameAtOrBefore(target)
                if (runVideoDecoder(vf, { AviVideoPacketSource(demux, key) }, surface, startUs, c)) return
            }
            runAviCpuVideo(demux, c, surface)
            return
        }
        val src = dataSource ?: return
        val fmt = exVideoFormat ?: return
        if (!runVideoDecoder(fmt, { ExtractorPacketSource(src, exVideoTrack, startUs) }, surface, startUs, c)) waitClockEnd(c)
    }

    private suspend fun runAviCpuVideo(demux: AviDemuxer, c: SwClock, surface: Surface) {
        var lastIdx = -1
        val frameUs = demux.frameDurationUs
        while (currentCoroutineContext().isActive) {
            val now = c.nowUs()
            val idx = (now / frameUs).toInt()
            if (idx > demux.videoChunks.lastIndex) break
            if (idx != lastIdx && surface.isValid) {
                val bmp = demux.decodeVideoFrameAt(idx)
                if (bmp != null) {
                    drawBitmapCenteredOnSurface(surface, bmp)
                    bmp.recycle()
                }
                lastIdx = idx
            }
            val nextUs = (idx + 1L) * frameUs
            delay(((nextUs - c.nowUs()) / 1000L / swSpeed).toLong().coerceIn(1L, 50L))
        }
        waitClockEnd(c)
    }

    private class DecoderStalled : Exception()

    private suspend fun runVideoDecoder(format: MediaFormat, sourceFactory: () -> SwPacketSource, surface: Surface, startUs: Long, c: SwClock?): Boolean {
        for (softwareOnly in booleanArrayOf(false, true)) {
            val src = try { sourceFactory() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { return false }
            try {
                return decodeVideo(format, src, surface, startUs, c, softwareOnly) ?: continue
            } catch (_: DecoderStalled) {
                continue
            } finally {
                src.close()
            }
        }
        return false
    }

    private suspend fun decodeVideo(format: MediaFormat, src: SwPacketSource, surface: Surface, startUs: Long, c: SwClock?, softwareOnly: Boolean): Boolean? {
        val codec = try {
            createDecoder(format, softwareOnly).also {
                try {
                    it.configure(format, surface, null, 0)
                    it.start()
                } catch (t: Throwable) {
                    try { it.release() } catch (_: Throwable) {}
                    throw t
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return null
        }
        try {
            val info = MediaCodec.BufferInfo()
            val pkt = SwPacket()
            var inEos = false
            var rendered = false
            var outputs = 0
            var lastProgressNs = System.nanoTime()
            while (currentCoroutineContext().isActive) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(5_000L)
                    if (ii >= 0) {
                        lastProgressNs = System.nanoTime()
                        val b = codec.getInputBuffer(ii)
                        b?.clear()
                        if (b != null && src.next(b, pkt)) {
                            codec.queueInputBuffer(ii, 0, pkt.size, pkt.ptsUs, 0)
                        } else {
                            codec.queueInputBuffer(ii, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5_000L)
                if (oi < 0) {
                    if (outputs == 0 && !softwareOnly && System.nanoTime() - lastProgressNs > 1_500_000_000L) throw DecoderStalled()
                    continue
                }
                lastProgressNs = System.nanoTime()
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (eos && info.size <= 0) {
                    codec.releaseOutputBuffer(oi, false)
                    break
                }
                val p = src.outputPts(outputs, info.presentationTimeUs)
                outputs++
                if (p < startUs - 1_000L && !eos) {
                    codec.releaseOutputBuffer(oi, false)
                    continue
                }
                if (c == null) {
                    codec.releaseOutputBuffer(oi, true)
                    delay(60)
                    break
                }
                while (currentCoroutineContext().isActive) {
                    val ahead = p - c.nowUs()
                    if (ahead <= 2_000L) break
                    delay((ahead / 1000L / swSpeed).toLong().coerceIn(1L, 20L))
                }
                if (!currentCoroutineContext().isActive) {
                    codec.releaseOutputBuffer(oi, false)
                    break
                }
                val late = c.nowUs() - p
                codec.releaseOutputBuffer(oi, surface.isValid && (!rendered || late < 100_000L))
                rendered = true
                if (eos) break
            }
            if (c != null && currentCoroutineContext().isActive) waitClockEnd(c)
            return true
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
        }
    }

    private inner class PcmSink(private val c: SwClock) {
        private var track: AudioTrack? = null
        private var rate = 0
        private var channels = 0
        private var encoding = 0
        private var frameBytes = 1
        private var written = 0L
        private val resampler = StreamingPcmResampler()

        private fun bytesPer(enc: Int) = when (enc) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 2
        }

        private fun mask(n: Int) = when (n) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
            else -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        }

        fun configure(r: Int, ch: Int, enc: Int, baseUs: Long): Boolean {
            if (track != null && r == rate && ch == channels && enc == encoding) return true
            finish()
            return try {
                val m = mask(ch)
                var chosenEnc = enc
                var minBuf = AudioTrack.getMinBufferSize(r, m, chosenEnc)
                if (minBuf <= 0 && chosenEnc != AudioFormat.ENCODING_PCM_16BIT) {
                    chosenEnc = AudioFormat.ENCODING_PCM_16BIT
                    minBuf = AudioTrack.getMinBufferSize(r, m, chosenEnc)
                }
                if (minBuf <= 0) return false
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (swHasVideo()) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setSampleRate(r)
                    .setChannelMask(m)
                    .setEncoding(chosenEnc)
                    .build()
                val t = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(maxOf(minBuf * 2, 16 * 1024))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                applyPreferredMixerAttributesIfSupported(attrs, t, r, chosenEnc)
                try { t.setVolume(swVolume) } catch (_: Throwable) {}
                if (swSpeed != 1f) try { t.playbackParams = android.media.PlaybackParams().setSpeed(swSpeed) } catch (_: Throwable) {}
                rate = r
                channels = ch
                encoding = chosenEnc
                frameBytes = ch * bytesPer(chosenEnc)
                written = 0L
                resampler.reset()
                track = t
                activeAudioTrack = t
                _activeAudioOutputConfig.value = t.sampleRate to t.audioFormat
                t.play()
                c.attachAudio(t, r, baseUs)
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun applyPreferredMixerAttributesIfSupported(
            attrs: AudioAttributes,
            audioTrack: AudioTrack,
            targetRate: Int,
            targetEnc: Int
        ) {
            if (Build.VERSION.SDK_INT < 34) return
            val am = audioManagerRef ?: return
            try {
                val dev = audioTrack.routedDevice ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull() ?: return
                val supported = am.getSupportedMixerAttributes(dev)
                val match = supported.firstOrNull { ma ->
                    ma.format.sampleRate == targetRate && ma.format.encoding == targetEnc
                } ?: supported.firstOrNull { ma ->
                    ma.format.sampleRate == targetRate
                }
                if (match != null) {
                    am.setPreferredMixerAttributes(attrs, dev, match)
                }
            } catch (_: Throwable) {}
        }

        fun writeDecoded(buf: ByteBuffer, inRate: Int, ch: Int, inEnc: Int, baseUs: Long): Boolean {
            val spec = audioQualitySpec
            val (targetRate, targetEnc) = AudioPcmQualityProcessor.resolveOutputFormat(inRate, inEnc, spec)
            if (!configure(targetRate, ch, targetEnc, baseUs)) return false
            if (inRate == rate && inEnc == encoding) {
                return write(buf)
            }
            val converted = resampler.process(buf, inRate, ch, inEnc, rate, encoding)
            if (!converted.hasRemaining()) return true
            return write(converted)
        }

        fun write(buf: ByteBuffer): Boolean {
            val t = track ?: return false
            val len = buf.remaining()
            while (buf.hasRemaining()) {
                val w = try { t.write(buf, buf.remaining(), AudioTrack.WRITE_BLOCKING) } catch (_: Throwable) { -1 }
                if (w <= 0) return false
            }
            written += len / frameBytes
            return true
        }

        suspend fun drain() {
            val t = track ?: return
            val head0 = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Throwable) { return }
            val remainingUs = (written - head0).coerceAtLeast(0L) * 1_000_000L / rate.coerceAtLeast(1)
            val deadline = System.nanoTime() + (remainingUs * 1000L / swSpeed).toLong() + 1_500_000_000L
            while (currentCoroutineContext().isActive && System.nanoTime() < deadline) {
                val head = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Throwable) { break }
                if (head >= written) break
                delay(15)
            }
        }

        fun finish() {
            val t = track ?: return
            track = null
            c.detachAudio(t)
            if (activeAudioTrack === t) {
                activeAudioTrack = null
                _activeAudioOutputConfig.value = 0 to 0
            }
            try {
                t.pause()
                t.flush()
            } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
        }
    }

    private suspend fun runAudio(c: SwClock, startUs: Long) {
        val sink = PcmSink(c)
        try {
            val demux = aviDemuxer
            if (demux != null) {
                val st = demux.audioStreams.getOrNull(selectedAudioId) ?: return
                when {
                    st.isRawPcm || st.isAdpcm -> runAviPcm(demux, st, startUs, sink)
                    else -> {
                        val fmt = aviAudioFormat(st) ?: return
                        runAudioDecoder(fmt, AviAudioPacketSource(demux, st, st.chunkAt(startUs)), startUs, sink)
                    }
                }
            } else {
                val src = dataSource ?: return
                val fmt = exAudioFormats[selectedAudioId] ?: return
                val packets = ExtractorPacketSource(src, selectedAudioId, startUs)
                try {
                    runAudioDecoder(fmt, packets, startUs, sink)
                } finally {
                    packets.close()
                }
            }
            sink.drain()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        } finally {
            sink.finish()
            c.detachAudio(null)
        }
    }

    private suspend fun runAviPcm(demux: AviDemuxer, st: AviDemuxer.AudioStream, startUs: Long, sink: PcmSink) {
        val ch = st.channels.coerceIn(1, 8)
        val bits = st.bitsPerSample
        val adpcm = st.isAdpcm
        val inEnc = when {
            adpcm -> AudioFormat.ENCODING_PCM_16BIT
            st.formatTag == 3 -> AudioFormat.ENCODING_PCM_FLOAT
            bits == 8 -> AudioFormat.ENCODING_PCM_8BIT
            bits == 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            bits == 32 && st.formatTag == 1 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        val inFrame = if (adpcm) st.blockAlign else (st.blockAlign.takeIf { it > 0 } ?: (ch * ((bits + 7) / 8)))
        val frameUnitUs = if (adpcm) {
            AdpcmDecoder.samplesPerBlock(st.formatTag, st.blockAlign, ch, st.extra).toLong() * 1_000_000L / st.sampleRate
        } else {
            1_000_000L / st.sampleRate.coerceAtLeast(1)
        }
        val first = st.chunkAt(startUs)
        val conv = java.io.ByteArrayOutputStream()
        for (i in first until st.chunks.size) {
            if (!currentCoroutineContext().isActive) break
            val bytes = demux.readChunkBytes(st.chunks[i]) ?: continue
            val chunkUs = st.chunkStartUs(i)
            var off = 0
            var baseUs = chunkUs
            if (i == first && startUs > chunkUs && inFrame > 0) {
                val units = if (adpcm) (startUs - chunkUs) / frameUnitUs.coerceAtLeast(1L) else (startUs - chunkUs) * st.sampleRate / 1_000_000L
                off = (units * inFrame).coerceIn(0L, (bytes.size / inFrame * inFrame).toLong()).toInt()
                baseUs = chunkUs + (if (adpcm) units * frameUnitUs else units * 1_000_000L / st.sampleRate)
            }
            conv.reset()
            when {
                adpcm -> AdpcmDecoder.decode(st.formatTag, bytes, off, st.blockAlign, ch, st.extra, conv)
                st.formatTag == 3 && bits == 64 -> {
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val ob = ByteBuffer.allocate((bytes.size - off) / 2).order(ByteOrder.LITTLE_ENDIAN)
                    var p = off
                    while (p + 8 <= bytes.size) {
                        ob.putFloat(bb.getDouble(p).toFloat())
                        p += 8
                    }
                    conv.write(ob.array(), 0, ob.position())
                }
                else -> conv.write(bytes, off, bytes.size - off)
            }
            if (conv.size() == 0) continue
            if (!sink.writeDecoded(ByteBuffer.wrap(conv.toByteArray()), st.sampleRate, ch, inEnc, baseUs)) break
        }
    }

    private suspend fun runAudioDecoder(format: MediaFormat, src: SwPacketSource, startUs: Long, sink: PcmSink) {
        val codec = createDecoder(format)
        try {
            val wantFloat = audioQualitySpec.targetBitDepth >= 24 || audioQualitySpec.targetSampleRateHz == 0
            var configured = false
            if (wantFloat) {
                try {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                    codec.configure(format, null, null, 0)
                    configured = true
                } catch (_: Throwable) {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
            }
            if (!configured) {
                codec.configure(format, null, null, 0)
            }
            codec.start()
            val info = MediaCodec.BufferInfo()
            val pkt = SwPacket()
            var inEos = false
            while (currentCoroutineContext().isActive) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(5_000L)
                    if (ii >= 0) {
                        val b = codec.getInputBuffer(ii)
                        b?.clear()
                        if (b != null && src.next(b, pkt)) {
                            codec.queueInputBuffer(ii, 0, pkt.size, pkt.ptsUs, 0)
                        } else {
                            codec.queueInputBuffer(ii, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5_000L)
                if (oi < 0) continue
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (info.size > 0) {
                    val of = codec.getOutputFormat(oi)
                    val r = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    val fb = ch * AudioPcmQualityProcessor.bytesPerSample(enc)
                    var off = info.offset
                    var len = info.size
                    var p = info.presentationTimeUs
                    if (p < startUs && r > 0) {
                        val drop = ((startUs - p) * r / 1_000_000L) * fb
                        if (drop >= len) {
                            len = 0
                        } else {
                            off += drop.toInt()
                            len -= drop.toInt()
                            p = startUs
                        }
                    }
                    if (len > 0) {
                        val ob = codec.getOutputBuffer(oi)
                        if (ob != null) {
                            ob.limit(off + len)
                            ob.position(off)
                            if (!sink.writeDecoded(ob, r, ch, enc, p)) {
                                codec.releaseOutputBuffer(oi, false)
                                break
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if (eos) break
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
        }
    }
}

class MediaEngineImpl : FileEngineProvider {
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

object MediaDrmDetector {
    const val MEDIA_DRM_ERROR =
        "DRM_PROTECTED: This media file is protected by cryptographic DRM. Circumventing DRM protection is prohibited under DMCA § 1201 and international copyright law."

    private val ISO_DRM_BOXES = setOf("pssh", "sinf", "schm", "encv", "enca", "drmi", "drms", "senc", "saiz", "saio")
    private val ISO_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "edts", "moof", "traf", "dinf")
    private val ISO_ROOT_BOXES = setOf("ftyp", "moov", "wide", "free", "mdat", "skip", "pnot", "pdin", "uuid", "moof", "styp")

    private val ASF_HEADER_GUID = byteArrayOf(
        0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
        0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C
    )
    private val ASF_CONTENT_ENCRYPTION_GUID = byteArrayOf(
        0xFB.toByte(), 0xB3.toByte(), 0x11, 0x22, 0x23, 0xBD.toByte(), 0xD2.toByte(), 0x11,
        0xB4.toByte(), 0xB7.toByte(), 0x00, 0xA0.toByte(), 0xC9.toByte(), 0x55, 0xFC.toByte(), 0x6E
    )
    private val ASF_EXT_CONTENT_ENCRYPTION_GUID = byteArrayOf(
        0x14, 0xE6.toByte(), 0x8A.toByte(), 0x29, 0x22, 0x26, 0x17, 0x4C,
        0xB9.toByte(), 0x35, 0xDA.toByte(), 0xE0.toByte(), 0x7E, 0xE9.toByte(), 0x28, 0x9C.toByte()
    )
    private val ASF_ADV_CONTENT_ENCRYPTION_GUID = byteArrayOf(
        0x33, 0x85.toByte(), 0x05, 0x43, 0x81.toByte(), 0x69, 0xE6.toByte(), 0x49,
        0x9B.toByte(), 0x74, 0xAD.toByte(), 0x12, 0xCB.toByte(), 0x86.toByte(), 0xD5.toByte(), 0x8C.toByte()
    )

    fun isDrmProtected(bytes: ByteArray): Boolean =
        isDrmProtected(bytes.size.toLong()) { offset, buf ->
            val pos = offset.toInt()
            if (pos < 0 || pos >= bytes.size) 0 else {
                val len = minOf(buf.remaining(), bytes.size - pos)
                buf.put(bytes, pos, len)
                len
            }
        }

    fun isDrmProtected(source: ZeroCacheReadableSource): Boolean =
        isDrmProtected(source.size) { offset, buf -> source.readAt(offset, buf) }

    fun isDrmProtected(size: Long, readAt: (Long, ByteBuffer) -> Int): Boolean {
        if (size < 12L) return false
        return try {
            val head = readSlice(0L, minOf(size, 64L).toInt(), readAt) ?: return false
            if (head.size >= 8) {
                val rootType = String(head, 4, 4, StandardCharsets.ISO_8859_1)
                if (rootType in ISO_ROOT_BOXES) {
                    return scanIsoBmffBoxes(0L, size, 0, intArrayOf(0), readAt)
                }
            }
            if (head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
            ) {
                val probeSize = minOf(size, 2L * 1024L * 1024L).toInt()
                val data = readSlice(0L, probeSize, readAt) ?: return false
                return scanMatroskaEncryption(data)
            }
            if (head.size >= 16 && matchesGuidAt(head, 0, ASF_HEADER_GUID)) {
                val probeSize = minOf(size, 65536L).toInt()
                val data = readSlice(0L, probeSize, readAt) ?: return false
                return containsGuid(data, ASF_CONTENT_ENCRYPTION_GUID) ||
                    containsGuid(data, ASF_EXT_CONTENT_ENCRYPTION_GUID) ||
                    containsGuid(data, ASF_ADV_CONTENT_ENCRYPTION_GUID)
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun matchesGuidAt(data: ByteArray, offset: Int, guid: ByteArray): Boolean {
        if (offset < 0 || offset + 16 > data.size) return false
        for (i in 0 until 16) {
            if (data[offset + i] != guid[i]) return false
        }
        return true
    }

    private fun containsGuid(data: ByteArray, guid: ByteArray): Boolean {
        val limit = data.size - 16
        var i = 0
        while (i <= limit) {
            if (data[i] == guid[0] && data[i + 1] == guid[1] && matchesGuidAt(data, i, guid)) return true
            i++
        }
        return false
    }

    private fun readSlice(offset: Long, len: Int, readAt: (Long, ByteBuffer) -> Int): ByteArray? {
        if (len <= 0) return null
        val buf = ByteBuffer.allocate(len)
        val r = readAt(offset, buf)
        if (r <= 0) return null
        return if (r == len) buf.array() else buf.array().copyOf(r)
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, o: Int): Long = (be32(b, o) shl 32) or be32(b, o + 4)

    private fun scanIsoBmffBoxes(
        start: Long,
        end: Long,
        depth: Int,
        visited: IntArray,
        readAt: (Long, ByteBuffer) -> Int
    ): Boolean {
        if (depth > 8 || visited[0] > 4096) return false
        var pos = start
        while (pos + 8L <= end && visited[0] < 4096) {
            visited[0]++
            val hdr = readSlice(pos, minOf(16L, end - pos).toInt(), readAt) ?: return false
            if (hdr.size < 8) return false
            var boxSize = be32(hdr, 0)
            val boxType = String(hdr, 4, 4, StandardCharsets.ISO_8859_1)
            var headerLen = 8L
            if (boxSize == 1L) {
                if (hdr.size < 16) return false
                boxSize = be64(hdr, 8)
                headerLen = 16L
            } else if (boxSize == 0L) {
                boxSize = end - pos
            }
            if (boxSize < headerLen || pos + boxSize > end) return false
            if (boxType in ISO_DRM_BOXES) return true
            val contentStart = pos + headerLen
            val boxEnd = pos + boxSize
            if (boxType == "stsd" && contentStart + 8L <= boxEnd) {
                if (scanIsoBmffBoxes(contentStart + 8L, boxEnd, depth + 1, visited, readAt)) return true
            } else if (boxType in ISO_CONTAINERS) {
                val childStart = if (boxType == "meta") {
                    val probe = readSlice(contentStart, minOf(8L, boxEnd - contentStart).toInt(), readAt)
                    if (probe != null && probe.size >= 4 && probe[0].toInt() == 0 && probe[1].toInt() == 0 && probe[2].toInt() == 0 && probe[3].toInt() == 0) {
                        contentStart + 4L
                    } else {
                        contentStart
                    }
                } else contentStart
                if (childStart < boxEnd && scanIsoBmffBoxes(childStart, boxEnd, depth + 1, visited, readAt)) return true
            }
            pos = boxEnd
        }
        return false
    }

    private fun readEbmlVint(b: ByteArray, pos: Int, maskMarker: Boolean): Pair<Long, Int>? {
        if (pos >= b.size) return null
        val first = b[pos].toInt() and 0xFF
        if (first == 0) return null
        var len = 1
        var mask = 0x80
        while (len <= 8 && (first and mask) == 0) {
            mask = mask ushr 1
            len++
        }
        if (len > 8 || pos + len > b.size) return null
        var value = (if (maskMarker) first and (mask - 1) else first).toLong()
        for (i in 1 until len) {
            value = (value shl 8) or (b[pos + i].toLong() and 0xFF)
        }
        return value to len
    }

    private fun scanMatroskaEncryption(data: ByteArray): Boolean {
        return scanEbmlRange(data, 0, data.size, 0, intArrayOf(0))
    }

    private fun scanEbmlRange(data: ByteArray, start: Int, end: Int, depth: Int, visited: IntArray): Boolean {
        if (depth > 8 || visited[0] > 8192) return false
        var pos = start
        while (pos < end && visited[0] < 8192) {
            visited[0]++
            val idPair = readEbmlVint(data, pos, false) ?: return false
            pos += idPair.second
            val sizePair = readEbmlVint(data, pos, true) ?: return false
            pos += sizePair.second
            val id = idPair.first
            val elemSize = sizePair.first
            val isUnknown = elemSize == ((1L shl (7 * sizePair.second)) - 1L)
            val elemEnd = if (isUnknown || elemSize < 0L || pos + elemSize > end) end else (pos + elemSize).toInt()
            if (id == 0x5035L) return true
            if (id == 0x5033L && elemSize in 1L..4L && pos + elemSize <= end) {
                var encType = 0L
                for (i in 0 until elemSize.toInt()) {
                    encType = (encType shl 8) or (data[pos + i].toLong() and 0xFF)
                }
                if (encType == 1L) return true
            }
            if (id == 0x18538067L || id == 0x1654AE6BL || id == 0xAEL || id == 0x6D80L || id == 0x6240L) {
                if (scanEbmlRange(data, pos, elemEnd, depth + 1, visited)) return true
            }
            if (isUnknown) break
            pos = elemEnd
        }
        return false
    }
}

class MediaPlaybackSession(private val pfd: ParcelFileDescriptor) : DocumentSession {
    @Volatile var isClosed = false
        private set
    @Volatile var sourceUri: String? = null
    private val hybridPlayer = HybridMediaPlayer()
    val mediaPlayer: MediaPlayer get() = hybridPlayer

    val isSoftwarePlayback: Boolean get() = hybridPlayer.isSoftwareAviMode

    fun detachSurface(surface: android.view.Surface) = hybridPlayer.detachSurface(surface)

    fun softwareAudioTracks(): List<SoftwareAudioTrack> = hybridPlayer.softwareAudioTracks()

    fun selectedSoftwareAudioTrack(): Int = hybridPlayer.selectedSoftwareAudio()

    fun selectSoftwareAudioTrack(id: Int) = hybridPlayer.selectSoftwareAudio(id)

    val embeddedSubtitles: StateFlow<List<EmbeddedSubtitle>> get() = hybridPlayer.embeddedSubtitles

    val activeAudioOutputConfig: StateFlow<Pair<Int, Int>> get() = hybridPlayer.activeAudioOutputConfig

    @Synchronized
    fun setAudioQualitySpec(spec: AudioQualitySpec, audioManager: AudioManager? = null) {
        if (isClosed) return
        val shouldPromote = spec.targetSampleRateHz > 0 && !hybridPlayer.isSoftwareAviMode && _isPrepared.value
        val wasPlaying = if (shouldPromote) try { hybridPlayer.isPlaying } catch (_: Throwable) { false } else false
        val savedPosMs = if (shouldPromote) try { hybridPlayer.currentPosition.coerceAtLeast(0) } catch (_: Throwable) { 0 } else 0
        val savedSpeed = if (shouldPromote) try { hybridPlayer.playbackParams.speed } catch (_: Throwable) { 1f } else 1f

        hybridPlayer.updateAudioQualitySpec(spec, audioManager)

        if (shouldPromote) {
            hybridPlayer.pauseNativeSuperQuietly()
            if (tryActivateInternalAviOrExtractorFallback()) {
                hybridPlayer.setSoftwareInitialState(savedPosMs, savedSpeed)
                if (wasPlaying) {
                    hybridPlayer.start()
                }
            }
        }
    }

    private val readableSource: ZeroCacheReadableSource by lazy { ZeroCacheReadableSource(pfd.fileDescriptor) }

    private val _isPrepared = MutableStateFlow(false)
    val isPrepared: StateFlow<Boolean> = _isPrepared.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    var trackTitle: String = ""
        private set
    var hasEmbeddedTitle: Boolean = false
        private set
    var trackArtist: String = ""
        private set
    var hasEmbeddedArtist: Boolean = false
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

    private fun tryActivateInternalAviOrExtractorFallback(): Boolean {
        return try {
            val source = readableSource
            val demuxer = AviDemuxer(source)
            if (demuxer.isValidAvi && hybridPlayer.activateInternalAviMode(demuxer)) {
                hasVideo = hybridPlayer.softwareHasVideo
                durationMs = demuxer.totalDurationMs
                if (albumArt == null && hasVideo) {
                    albumArt = demuxer.decodeVideoFrameAt(0)
                }
                hybridPlayer.previewFallbackBitmap = albumArt
                _playbackError.value = null
                _isPrepared.value = true
                return true
            }

            val extFallback = hybridPlayer.activateMediaExtractorCodecFallback(source)
            if (extFallback != null) {
                hasVideo = extFallback.first
                durationMs = extFallback.second
                hybridPlayer.previewFallbackBitmap = albumArt
                hybridPlayer.loadEmbeddedSubtitles(source)
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
        } else if (MediaDrmDetector.isDrmProtected(readableSource)) {
            _playbackError.value = MediaDrmDetector.MEDIA_DRM_ERROR
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

                if (!title.isNullOrEmpty()) {
                    trackTitle = title
                    hasEmbeddedTitle = true
                }
                if (!artist.isNullOrEmpty()) {
                    trackArtist = artist
                    hasEmbeddedArtist = true
                }
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
                            if (!tryActivateInternalAviOrExtractorFallback()) {
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
                            hybridPlayer.loadEmbeddedSubtitles(readableSource)
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
                            hybridPlayer.loadEmbeddedSubtitles(readableSource)
                            _isPrepared.value = true
                        }
                    }
                } else {
                    hybridPlayer.prepareAsync()
                }
            } catch (e: Exception) {
                if (!tryActivateInternalAviOrExtractorFallback()) {
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
