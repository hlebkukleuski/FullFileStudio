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

package me.hlebkukleuski.fullfilestudio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import me.hlebkukleuski.fullfilestudio.data.settings.AppAudioQualityMode
import me.hlebkukleuski.fullfilestudio.data.settings.AppLanguage
import me.hlebkukleuski.fullfilestudio.ui.i18n.stringsForLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSecurityAndLocaleTest {

    @Test
    fun ukLocaleUsesStandardUkrainian() {
        assertEquals("Українська", AppLanguage.UK.nativeName)
        val uk = stringsForLanguage(AppLanguage.UK)
        assertEquals("Продовжити", uk.continueButton)
        assertEquals("Відкрити файл", uk.openFileSaf)
        assertFalse(uk.frameRateAutoPattern.contains("тєлєфон"))
        assertFalse(uk.downloadingUpdateStatus.contains("Качаєм"))
    }

    @Test
    fun filePathsXmlExistsOnlyInStandaloneAndExposesOnlyUpdatesCacheSubdirectory() {
        val mainCandidates = listOf(
            File("src/main/res/xml/file_paths.xml"),
            File("app/src/main/res/xml/file_paths.xml")
        )
        assertTrue("file_paths.xml must not exist in shared/googlePlay main res", mainCandidates.none { it.exists() })

        val standaloneCandidates = listOf(
            File("src/standalone/res/xml/file_paths.xml"),
            File("app/src/standalone/res/xml/file_paths.xml")
        )
        val xmlFile = standaloneCandidates.firstOrNull { it.exists() }
        assertTrue("standalone file_paths.xml must exist", xmlFile != null)
        val text = xmlFile!!.readText()
        assertFalse("file_paths.xml must not expose path=\".\"", text.contains("path=\".\""))
        assertFalse("file_paths.xml must not expose files-path", text.contains("<files-path"))
        assertFalse("file_paths.xml must not expose external-path", text.contains("<external-path"))
        assertTrue("file_paths.xml must expose updates/ cache path", text.contains("path=\"updates/\""))
    }

    @Test
    fun audioQualityModesMapToExactSampleRatesAndBitDepths() {
        assertEquals(AppAudioQualityMode.STUDIO_96, AppAudioQualityMode.fromId("96_24"))
        assertEquals(96_000, AppAudioQualityMode.STUDIO_96.sampleRateHz)
        assertEquals(24, AppAudioQualityMode.STUDIO_96.bitDepth)
        assertEquals(AppAudioQualityMode.HIGH_48, AppAudioQualityMode.fromId("48_16"))
        assertEquals(48_000, AppAudioQualityMode.HIGH_48.sampleRateHz)
        assertEquals(16, AppAudioQualityMode.HIGH_48.bitDepth)
        assertEquals(AppAudioQualityMode.STANDARD_44, AppAudioQualityMode.fromId("44_16"))
        assertEquals(44_100, AppAudioQualityMode.STANDARD_44.sampleRateHz)
        assertEquals(16, AppAudioQualityMode.STANDARD_44.bitDepth)
        assertEquals(AppAudioQualityMode.AUTO, AppAudioQualityMode.fromId("unknown"))
    }

    @Test
    fun baselineProfileEntriesMatchCompiledBytecodeDescriptors() {
        val profFile = listOf(
            File("src/main/baseline-prof.txt"),
            File("app/src/main/baseline-prof.txt")
        ).firstOrNull { it.exists() }
        assertNotNull("baseline-prof.txt must exist", profFile)

        val rules = profFile!!.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("Expected comprehensive baseline profile (>= 300 rules), got ${rules.size}", rules.size >= 300)

        val loader = javaClass.classLoader!!
        val methodsByClass = HashMap<String, Set<String>>()

        fun loadClassMethods(internalName: String): Set<String> {
            methodsByClass[internalName]?.let { return it }
            val stream = loader.getResourceAsStream("$internalName.class")
            assertNotNull("Missing compiled class $internalName.class on classpath", stream)
            val raw = stream!!.use { it.readBytes() }
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            assertEquals(0xCAFEBABE.toInt(), bb.getInt(0))
            var pos = 8
            val cpCount = bb.getShort(pos).toInt() and 0xFFFF
            pos += 2
            val utf8 = arrayOfNulls<String>(cpCount)
            var idx = 1
            while (idx < cpCount) {
                val tag = raw[pos].toInt() and 0xFF
                pos++
                when (tag) {
                    1 -> {
                        val len = bb.getShort(pos).toInt() and 0xFFFF
                        pos += 2
                        utf8[idx] = String(raw, pos, len, StandardCharsets.UTF_8)
                        pos += len
                    }
                    7, 8, 16, 19, 20 -> pos += 2
                    3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4
                    5, 6 -> {
                        pos += 8
                        idx++
                    }
                    15 -> pos += 3
                }
                idx++
            }
            pos += 6
            val ifCount = bb.getShort(pos).toInt() and 0xFFFF
            pos += 2 + 2 * ifCount
            val fCount = bb.getShort(pos).toInt() and 0xFFFF
            pos += 2
            repeat(fCount) {
                val ac = bb.getShort(pos + 6).toInt() and 0xFFFF
                pos += 8
                repeat(ac) {
                    val al = bb.getInt(pos + 2)
                    pos += 6 + al
                }
            }
            val mCount = bb.getShort(pos).toInt() and 0xFFFF
            pos += 2
            val set = HashSet<String>(mCount * 2)
            repeat(mCount) {
                val nIdx = bb.getShort(pos + 2).toInt() and 0xFFFF
                val dIdx = bb.getShort(pos + 4).toInt() and 0xFFFF
                val ac = bb.getShort(pos + 6).toInt() and 0xFFFF
                pos += 8
                set.add("${utf8[nIdx]}${utf8[dIdx]}")
                repeat(ac) {
                    val al = bb.getInt(pos + 2)
                    pos += 6 + al
                }
            }
            methodsByClass[internalName] = set
            return set
        }

        for (rule in rules) {
            val lPos = rule.indexOf('L')
            assertTrue("Invalid rule syntax: $rule", lPos >= 0)
            val arrowPos = rule.indexOf("->")
            if (arrowPos < 0) {
                assertTrue("Class rule must end with ';': $rule", rule.endsWith(";"))
                val cls = rule.substring(lPos + 1, rule.length - 1)
                loadClassMethods(cls)
            } else {
                val cls = rule.substring(lPos + 1, arrowPos - 1)
                val sig = rule.substring(arrowPos + 2)
                val methods = loadClassMethods(cls)
                assertTrue("Method descriptor $sig not found in class $cls", methods.contains(sig))
            }
        }
    }
}
