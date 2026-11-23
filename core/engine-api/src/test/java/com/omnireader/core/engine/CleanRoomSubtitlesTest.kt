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

import com.omnireader.core.engine.impl.CleanRoomSubtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CleanRoomSubtitlesTest {

    @Test
    fun srtInCp1251() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nПривет, <i>мир</i>\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nLine one\r\nLine two\r\n"
        val t = CleanRoomSubtitles.parse(srt.toByteArray(charset("windows-1251")), "movie.srt")!!
        assertNull(t.textAt(500))
        assertEquals("Привет, мир", t.textAt(1500))
        assertNull(t.textAt(2600))
        assertEquals("Line one\nLine two", t.textAt(3500))
    }

    @Test
    fun webVtt() {
        val vtt = "WEBVTT\n\nintro\n00:01.000 --> 00:02.000 align:start\nHello &amp; <b>bye</b>\n\n00:00:01.500 --> 00:00:03.000\nOverlap\n"
        val t = CleanRoomSubtitles.parse(vtt.toByteArray(), "a.vtt")!!
        assertEquals("Hello & bye\nOverlap", t.textAt(1700))
        assertEquals("Overlap", t.textAt(2500))
    }

    @Test
    fun assLrcSbvMicroDvd() {
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\b1}Bold, text\\Nnext\n"
        assertEquals("Bold, text\nnext", CleanRoomSubtitles.parse(ass.toByteArray(), "a.ass")!!.textAt(1500))
        val lrc = "[ar:Someone]\n[00:01.50]First line\n[00:04.00][00:10.00]Chorus\n"
        val l = CleanRoomSubtitles.parse(lrc.toByteArray(), "song.lrc")!!
        assertEquals("First line", l.textAt(2000))
        assertEquals("Chorus", l.textAt(5000))
        assertEquals("Chorus", l.textAt(12000))
        val sbv = "0:00:01.000,0:00:02.000\nSbv text\n"
        assertEquals("Sbv text", CleanRoomSubtitles.parse(sbv.toByteArray(), "a.sbv")!!.textAt(1500))
        val sub = "{1}{1}25\n{25}{50}One|Two\n"
        assertEquals("One\nTwo", CleanRoomSubtitles.parse(sub.toByteArray(), "a.sub")!!.textAt(1500))
    }
}
