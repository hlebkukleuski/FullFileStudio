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

package me.hlebkukleuski.fullfilestudio.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PaddleOcrManagerTest {

    private data class DrawnLine(val text: String, val left: Float, val baseline: Float, val textSize: Float)

    private val pageWidth = 1400
    private val pageHeight = 900

    private fun textPaint(textSize: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        this.textSize = textSize
        typeface = Typeface.SANS_SERIF
    }

    private fun renderPage(
        lines: List<DrawnLine>,
        background: Int = Color.WHITE,
        foreground: Int = Color.BLACK,
        typeface: Typeface = Typeface.SANS_SERIF
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        for (line in lines) {
            val paint = textPaint(line.textSize)
            paint.color = foreground
            paint.typeface = typeface
            canvas.drawText(line.text, line.left, line.baseline, paint)
        }
        return bitmap
    }

    private fun recognizedTexts(lines: List<DrawnLine>, bitmap: Bitmap = renderPage(lines)): List<String> =
        recognize(bitmap).lines.map { it.text }

    private fun recognize(
        bitmap: Bitmap,
        hint: String = "",
        zoomPermille: Int = 1000,
        tileX: Int = 0,
        tileY: Int = 0,
        languageCode: String = "en"
    ): OcrResultPayload = runBlocking {
        PaddleOcrManager.recognize(
            bitmap = bitmap,
            pageTextHint = hint,
            zoomPermille = zoomPermille,
            tileX = tileX,
            tileY = tileY,
            docWidth = pageWidth.toFloat(),
            docHeight = pageHeight.toFloat(),
            preferredLanguageCode = languageCode
        )
    }

    private fun pdfBoxHint(line: DrawnLine): String {
        val paint = textPaint(line.textSize)
        val metrics = paint.fontMetrics
        val left = line.left / pageWidth
        val right = (line.left + paint.measureText(line.text)) / pageWidth
        val top = (line.baseline + metrics.ascent) / pageHeight
        val bottom = (line.baseline + metrics.descent) / pageHeight
        return String.format(Locale.US, "[PDF_BOX:%.4f,%.4f,%.4f,%.4f]%s", left, top, right, bottom, line.text)
    }

    private fun assertCharsFollowGlyphs(
        line: DrawnLine,
        chars: List<OcrRecognizedChar>,
        scale: Float,
        shiftX: Float,
        typeface: Typeface = Typeface.SANS_SERIF
    ) {
        val paint = textPaint(line.textSize)
        paint.typeface = typeface
        val graphemes = PaddleOcrManager.splitIntoGraphemes(line.text)
        assertEquals(line.text, graphemes.size, chars.size)
        val tolerance = line.textSize * scale * 0.30f
        var consumed = 0
        for (i in graphemes.indices) {
            val expectedLeft = (line.left + paint.measureText(line.text, 0, consumed)) * scale - shiftX
            consumed += graphemes[i].length
            val expectedRight = (line.left + paint.measureText(line.text, 0, consumed)) * scale - shiftX
            val rect = chars[i].rect
            assertEquals(graphemes[i], chars[i].text)
            if (graphemes[i].isBlank()) continue
            assertTrue(
                "${line.text} [$i] '${graphemes[i]}' left ${rect.left} vs $expectedLeft",
                abs(rect.left - expectedLeft) <= tolerance
            )
            assertTrue(
                "${line.text} [$i] '${graphemes[i]}' right ${rect.right} vs $expectedRight",
                abs(rect.right - expectedRight) <= tolerance
            )
            if (i > 0) {
                assertTrue("${line.text} [$i] overlaps previous", rect.left >= chars[i - 1].rect.left)
            }
        }
    }

    @Before
    fun setUp() {
        runBlocking { PaddleOcrManager.initialize(ApplicationProvider.getApplicationContext<Context>()) }
    }

    @After
    fun tearDown() {
        PaddleOcrManager.release()
    }

    @Test
    fun recognizesRussianPolishGermanAndEnglishRasterLines() {
        val lines = listOf(
            DrawnLine("Съешь ещё этих мягких французских булок", 60f, 120f, 44f),
            DrawnLine("Zażółć gęślą jaźń i wypij trochę", 60f, 260f, 44f),
            DrawnLine("Über Straße größer façade", 60f, 400f, 44f),
            DrawnLine("The quick brown fox jumps over 1234", 60f, 540f, 44f),
            DrawnLine("Договор № 17 от 05.03.2024 г. Москва", 60f, 680f, 44f)
        )

        assertEquals(lines.map { it.text }, recognizedTexts(lines))
    }

    @Test
    fun recognizesTextFromSmallToHeadlineSizes() {
        for (textSize in listOf(14f, 20f, 28f, 44f, 60f, 80f, 96f)) {
            val lines = listOf(
                DrawnLine("Съешь ещё этих", 60f, 200f, textSize),
                DrawnLine("The quick brown fox jumps", 60f, 500f, textSize)
            )

            assertEquals("text size $textSize", lines.map { it.text }, recognizedTexts(lines))
        }
    }

    @Test
    fun recognizesLightTextOnDarkBackground() {
        val lines = listOf(
            DrawnLine("Тёмная тема: белый текст", 60f, 200f, 40f),
            DrawnLine("Dark theme light text 2024", 60f, 400f, 40f)
        )
        val bitmap = renderPage(lines, background = Color.rgb(22, 23, 30), foreground = Color.rgb(232, 235, 244))

        assertEquals(lines.map { it.text }, recognizedTexts(lines, bitmap))
    }

    @Test
    fun keepsTightParagraphLinesSeparate() {
        val texts = listOf(
            "Первая строка плотного абзаца текста,",
            "вторая строка идёт сразу под ней,",
            "третья строка завершает абзац.",
            "Zwykły akapit po polsku: żółw i gęś.",
            "Druga linia tego samego akapitu."
        )
        val lines = texts.mapIndexed { i, text -> DrawnLine(text, 80f, 120f + i * 38f, 30f) }

        assertEquals(texts, recognizedTexts(lines))
    }

    @Test
    fun readsTableColumnsAsSeparateCellsColumnByColumn() {
        val lines = ArrayList<DrawnLine>()
        for (row in 0 until 4) {
            lines.add(DrawnLine("Товар номер ${row + 1}", 80f, 150f + row * 70f, 28f))
            lines.add(DrawnLine("${(row + 1) * 1250},00 руб.", 760f, 150f + row * 70f, 28f))
        }

        assertEquals(
            listOf(
                "Товар номер 1", "Товар номер 2", "Товар номер 3", "Товар номер 4",
                "1250,00 руб.", "2500,00 руб.", "3750,00 руб.", "5000,00 руб."
            ),
            recognizedTexts(lines)
        )
    }

    @Test
    fun recognizesSerifAndMonospaceTypefaces() {
        val serif = listOf(
            DrawnLine("Глава 1. Введение в предмет", 60f, 200f, 36f),
            DrawnLine("The quick brown fox jumps", 60f, 320f, 36f)
        )
        assertEquals(serif.map { it.text }, recognizedTexts(serif, renderPage(serif, typeface = Typeface.SERIF)))

        val mono = listOf(DrawnLine("val total = items.sum() // 42", 60f, 200f, 36f))
        assertEquals(mono.map { it.text }, recognizedTexts(mono, renderPage(mono, typeface = Typeface.MONOSPACE)))
    }

    @Test
    fun wordsInMixedCyrillicAndLatinLinesKeepTheirOwnScript() {
        val lines = listOf(DrawnLine("Счёт на оплату Invoice total 1500 USD", 60f, 200f, 34f))

        assertEquals(lines.map { it.text }, recognizedTexts(lines))
    }

    @Test
    fun rasterCharacterBoxesFollowTheRenderedGlyphs() {
        for (typeface in listOf(Typeface.SANS_SERIF, Typeface.SERIF)) {
            for (textSize in listOf(18f, 30f, 48f)) {
                val lines = listOf(
                    DrawnLine("Съешь ещё этих мягких французских булок", 70f, 150f, textSize),
                    DrawnLine("The quick brown fox jumps over 1234", 70f, 330f, textSize),
                    DrawnLine("Zażółć gęślą jaźń i wypij trochę", 70f, 510f, textSize),
                    DrawnLine("Договор № 17 от 05.03.2024 г.", 70f, 690f, textSize)
                )
                val payload = recognize(renderPage(lines, typeface = typeface))

                assertEquals(lines.map { it.text }, payload.lines.map { it.text })
                for (lineIndex in lines.indices) {
                    assertCharsFollowGlyphs(
                        lines[lineIndex],
                        payload.chars.filter { it.lineIndex == lineIndex },
                        1f,
                        0f,
                        typeface
                    )
                }
            }
        }
    }

    @Test
    fun pdfNativeLinesGetCharacterBoxesOnTheRenderedGlyphs() {
        val lines = listOf(
            DrawnLine("Hello wide world", 100f, 200f, 48f),
            DrawnLine("Миру мир и труд", 100f, 400f, 48f)
        )
        val payload = recognize(renderPage(lines), hint = lines.joinToString("\n") { pdfBoxHint(it) })

        assertEquals(lines.map { it.text }, payload.lines.map { it.text })
        for (lineIndex in lines.indices) {
            assertCharsFollowGlyphs(lines[lineIndex], payload.chars.filter { it.lineIndex == lineIndex }, 1f, 0f)
        }
    }

    @Test
    fun pdfNativeLineRunningPastTheBufferKeepsItsTrueExtent() {
        val line = DrawnLine("Zoomed line that leaves the visible buffer", 100f, 450f, 40f)
        val zoom = 2f
        val tileX = 300
        val zoomedBitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(zoomedBitmap)
        canvas.drawColor(Color.WHITE)
        val originX = (pageWidth - pageWidth * zoom) / 2f - tileX
        val originY = (pageHeight - pageHeight * zoom) / 2f
        canvas.translate(originX, originY)
        canvas.scale(zoom, zoom)
        canvas.drawText(line.text, line.left, line.baseline, textPaint(line.textSize))

        val payload = recognize(zoomedBitmap, hint = pdfBoxHint(line), zoomPermille = 2000, tileX = tileX)

        assertEquals(listOf(line.text), payload.lines.map { it.text })
        val chars = payload.chars
        val expectedLeft = line.left * zoom + originX
        val expectedRight = (line.left + textPaint(line.textSize).measureText(line.text)) * zoom + originX
        assertTrue("line must start left of the buffer, was $expectedLeft", expectedLeft < 0f)
        assertTrue(abs(chars.first().rect.left - expectedLeft) <= 6f)
        assertTrue(abs(chars.last().rect.right - expectedRight) <= 6f)
        assertCharsFollowGlyphs(line, chars, zoom, -originX)
    }

    @Test
    fun hybridPdfPageKeepsNativeAndRasterLinesInReadingOrder() {
        val nativeTop = DrawnLine("Native heading text", 100f, 150f, 48f)
        val rasterMiddle = DrawnLine("Скан печати организации", 100f, 430f, 48f)
        val nativeBottom = DrawnLine("Native footer text", 100f, 720f, 48f)
        val bitmap = renderPage(listOf(nativeTop, rasterMiddle, nativeBottom))
        val hint = pdfBoxHint(nativeBottom) + "\n" + pdfBoxHint(nativeTop)

        val payload = recognize(bitmap, hint = hint)

        assertEquals(listOf(nativeTop.text, rasterMiddle.text, nativeBottom.text), payload.lines.map { it.text })
        assertEquals(listOf(0, 1, 2), payload.chars.map { it.lineIndex }.distinct())
    }

    @Test
    fun nativePageTextReplacesRecognitionThatContradictsIt() {
        val drawn = listOf(
            DrawnLine("Qwrt zxcv plmk njhb vgyt", 100f, 250f, 48f),
            DrawnLine("Wsxe dcrf vtgb yhnu jmik", 100f, 500f, 48f)
        )
        val nativeLines = listOf("Первая строка настоящего текста", "Вторая строка этого документа")

        val payload = recognize(renderPage(drawn), hint = nativeLines.joinToString("\n"))

        assertEquals(nativeLines, payload.lines.map { it.text })
    }

    @Test
    fun recognitionThatAgreesWithNativePageTextIsKept() {
        val drawn = listOf(
            DrawnLine("Первая строка настоящего текста", 100f, 250f, 48f),
            DrawnLine("Вторая строка этого документа", 100f, 500f, 48f)
        )

        val payload = recognize(renderPage(drawn), hint = drawn.joinToString("\n") { it.text })

        assertEquals(drawn.map { it.text }, payload.lines.map { it.text })
        val firstLineChars = payload.chars.filter { it.lineIndex == 0 }
        val inkRight = drawn[0].left + textPaint(48f).measureText(drawn[0].text)
        assertTrue(abs(firstLineChars.last().rect.right - inkRight) <= 20f)
    }

    @Test
    fun homoglyphsInsideOneWordAreNormalizedOnlyWhenOneScriptIsUnambiguous() {
        assertEquals("оплату", PaddleOcrManager.normalizeIntraWordHomoglyphs("oплатy"))
        assertEquals("Москва", PaddleOcrManager.normalizeIntraWordHomoglyphs("Mocква"))
        assertEquals("Invoice", PaddleOcrManager.normalizeIntraWordHomoglyphs("Invоiсе"))
        assertEquals("infо@ехатрlе.com", PaddleOcrManager.normalizeIntraWordHomoglyphs("infо@ехатрlе.com"))
        assertEquals("Привет", PaddleOcrManager.normalizeIntraWordHomoglyphs("Привет"))
        assertEquals("Hello", PaddleOcrManager.normalizeIntraWordHomoglyphs("Hello"))
    }
}
