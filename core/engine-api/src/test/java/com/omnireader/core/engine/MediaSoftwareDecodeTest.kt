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

package com.omnireader.core.engine

import com.omnireader.core.engine.impl.CleanRoomAdpcm
import com.omnireader.core.engine.impl.CleanRoomMatroskaSubtitles
import com.omnireader.core.engine.impl.CleanRoomSubtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSoftwareDecodeTest {

    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("adpcm/$name")!!.readBytes()

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    private fun checkAdpcm(codec: String, channels: Int) {
        val wav = res("${codec}_$channels.wav")
        val ref = res("${codec}_$channels.pcm")
        var p = 12
        var tag = 0
        var ch = 0
        var blockAlign = 0
        var extra: ByteArray? = null
        var dataOff = -1
        var dataLen = 0
        while (p + 8 <= wav.size) {
            val id = String(wav, p, 4, Charsets.US_ASCII)
            val size = le32(wav, p + 4)
            if (id == "fmt ") {
                tag = le16(wav, p + 8)
                ch = le16(wav, p + 10)
                blockAlign = le16(wav, p + 20)
                val cb = le16(wav, p + 24)
                extra = wav.copyOfRange(p + 26, p + 26 + cb)
            } else if (id == "data") {
                dataOff = p + 8
                dataLen = size
            }
            p += 8 + size + (size and 1)
        }
        assertEquals(channels, ch)
        val out = java.io.ByteArrayOutputStream()
        CleanRoomAdpcm.decode(tag, wav.copyOfRange(dataOff, dataOff + dataLen), 0, blockAlign, ch, extra, out)
        val got = out.toByteArray()
        assertTrue(got.size > ref.size / 2)
        val n = minOf(got.size, ref.size)
        var maxDiff = 0
        for (i in 0 until n step 2) {
            val a = (le16(got, i).toShort()).toInt()
            val b = (le16(ref, i).toShort()).toInt()
            maxDiff = maxOf(maxDiff, kotlin.math.abs(a - b))
        }
        assertEquals("$codec/$channels", 0, maxDiff)
    }

    @Test
    fun imaAdpcmMatchesReference() {
        checkAdpcm("adpcm_ima_wav", 1)
        checkAdpcm("adpcm_ima_wav", 2)
    }

    @Test
    fun msAdpcmMatchesReference() {
        checkAdpcm("adpcm_ms", 1)
        checkAdpcm("adpcm_ms", 2)
    }

    @Test
    fun matroskaTextTracks() {
        val mkv = javaClass.classLoader!!.getResourceAsStream("mkvsubs/two_subs.mkv")!!.readBytes()
        val reader = CleanRoomMatroskaSubtitles.Reader { off, dst, dstOff, len ->
            if (off >= mkv.size) -1 else {
                val n = minOf(len.toLong(), mkv.size - off).toInt()
                System.arraycopy(mkv, off.toInt(), dst, dstOff, n)
                n
            }
        }
        val subs = CleanRoomMatroskaSubtitles.read(reader, mkv.size.toLong())
        assertEquals(2, subs.size)
        assertEquals("Forced", subs[0].label)
        assertEquals("rus", subs[0].language)
        assertNull(subs[0].track.textAt(900))
        assertEquals("Первая строка субтитров", subs[0].track.textAt(1_000))
        assertEquals("Second cue here", subs[0].track.textAt(8_400))
        assertNull(subs[0].track.textAt(8_600))
        assertEquals("Третья реплика", subs[0].track.textAt(13_900))
        assertEquals("eng", subs[1].language)
        assertEquals("Hello\nworld, again", subs[1].track.textAt(3_000))
        assertNull(subs[1].track.textAt(3_800))
        assertTrue(CleanRoomMatroskaSubtitles.read(reader, 3L).isEmpty())
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    @Test
    fun embeddedSampleFormats() {
        fun tx3g(s: String): ByteArray {
            val b = s.toByteArray(Charsets.UTF_8)
            return byteArrayOf((b.size ushr 8).toByte(), b.size.toByte()) + b
        }
        val mov = CleanRoomSubtitles.fromSamples(
            "text/3gpp-tt",
            listOf(0L to tx3g(""), 1_000_000L to tx3g("Первая"), 4_000_000L to tx3g(""), 5_000_000L to tx3g("Second")),
            9_000L
        )!!
        assertNull(mov.textAt(500))
        assertEquals("Первая", mov.textAt(3_999))
        assertNull(mov.textAt(4_500))
        assertEquals("Second", mov.textAt(8_900))

        val vtt = CleanRoomSubtitles.fromSamples(
            "text/vtt",
            listOf(2_000_000L to box("vttc", box("sttg", "align:start".toByteArray()) + box("payl", "<b>Bold</b> line".toByteArray())), 3_000_000L to box("vtte", ByteArray(0)))
        )!!
        assertEquals("Bold line", vtt.textAt(2_500))
        assertNull(vtt.textAt(3_100))

        val ssa = CleanRoomSubtitles.fromSamples("text/x-ssa", listOf(1_000_000L to "3,0,Default,,0,0,0,,{\\i1}Hi\\Nthere, friend".toByteArray()))!!
        assertEquals("Hi\nthere, friend", ssa.textAt(1_500))

        val srt = CleanRoomSubtitles.fromSamples("application/x-subrip", listOf(0L to "a".toByteArray(), 10_000_000L to "b".toByteArray()))
        assertNotNull(srt)
        assertEquals("a", srt!!.textAt(1_000))
        assertNull(srt.textAt(9_000))
    }
}
