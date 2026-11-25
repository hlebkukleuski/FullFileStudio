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

import android.graphics.Color
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExcelNumberFormatTest {

    @Before
    fun locale() {
        ExcelNumberFormat.systemLocale = Locale.US
    }

    private fun f(v: Double, code: String) = ExcelNumberFormat.formatCode(v, code).text

    @Test
    fun general() {
        assertEquals("0", f(0.0, "General"))
        assertEquals("42", f(42.0, "General"))
        assertEquals("0.333333333", f(1.0 / 3.0, "General"))
        assertEquals("1234.567891", f(1234.5678912345, "General"))
        assertEquals("0.3", f(0.1 + 0.2, "General"))
        assertEquals("1.23457E+11", f(123456789012.0, "General"))
        assertEquals("1.2345E-05", f(0.000012345, "General"))
        assertEquals("-2.5", f(-2.5, "General"))
        assertTrue(ExcelNumberFormat.format(5.0, 0, null).general)
    }

    @Test
    fun numbers() {
        assertEquals("1,234,568", f(1234567.89, "#,##0"))
        assertEquals("1,234,567.89", f(1234567.891, "#,##0.00"))
        assertEquals("0", f(0.0, "#,##0"))
        assertEquals("0.50", f(0.5, "0.00"))
        assertEquals(".50", f(0.5, "#.00"))
        assertEquals("2.68", f(2.675, "0.00"))
        assertEquals("00042", f(42.0, "00000"))
        assertEquals("5.", f(5.0, "#.##"))
        assertEquals("5.1", f(5.1, "#.##"))
        assertEquals("1.2", f(1.2, "0.0#"))
        assertEquals("1.25", f(1.25, "0.0#"))
        assertEquals("1,235", f(1234567.0, "#,##0,"))
        assertEquals("1.2", f(1234567.0, "0.0,,"))
        assertEquals("-1,235", f(-1234.5, "#,##0"))
        assertEquals("(1,235)", f(-1234.5, "#,##0;(#,##0)"))
        assertEquals("zero", f(0.0, "0;-0;\"zero\""))
        assertEquals("", f(0.0, "0;-0;;@"))
        assertEquals("12%", f(0.123, "0%"))
        assertEquals("12.30%", f(0.123, "0.00%"))
        assertEquals("1.23E+04", f(12345.0, "0.00E+00"))
        assertEquals("1.23E-03", f(0.00123, "0.00E+00"))
        assertEquals("12.3E+3", f(12345.0, "##0.0E+0"))
        assertEquals("(555) 123-4567", f(5551234567.0, "(###) ###-####"))
        assertEquals("$1,234.50", f(1234.5, "\$#,##0.00"))
        assertEquals("-\$5", f(-5.0, "\$#,##0"))
        assertEquals("1,234.50 ₽", f(1234.5, "#,##0.00\\ [\$₽-419]"))
        assertEquals("€12", f(12.0, "[\$€-407]0"))
        ExcelNumberFormat.systemLocale = Locale.forLanguageTag("ru-RU")
        assertEquals("1\u00A0234\u00A0567,89", f(1234567.891, "#,##0.00"))
        assertEquals("0,333333333", f(1.0 / 3.0, "General"))
        assertEquals("12,3%", f(0.123, "0.0%"))
        assertEquals("15.01.2024", f(45306.0, "dd.mm.yyyy"))
    }

    @Test
    fun colorsAndConditions() {
        val red = ExcelNumberFormat.formatCode(-3.0, "0;[Red]-0")
        assertEquals("-3", red.text)
        assertEquals(Color.rgb(255, 0, 0), red.color)
        val r8 = ExcelNumberFormat.format(-3.0, 8, null)
        assertEquals("(\$3.00)", r8.text.trim())
        assertEquals(Color.rgb(255, 0, 0), r8.color)
        assertEquals("big", f(500.0, "[>100]\"big\";[<=100]\"small\""))
        assertEquals("small", f(50.0, "[>100]\"big\";[<=100]\"small\""))
    }

    @Test
    fun accounting() {
        val r = ExcelNumberFormat.format(1234.0, 44, null)
        assertEquals(" \$1,234.00 ", r.text)
        assertEquals(2, r.fillAt)
        assertEquals(' ', r.fillChar)
        assertEquals(" \$(1,234.00)", ExcelNumberFormat.format(-1234.0, 44, null).text)
        assertEquals(" \$-   ", ExcelNumberFormat.format(0.0, 44, null).text)
        assertEquals(" abc ", ExcelNumberFormat.formatText("abc", 44, null).text)
    }

    @Test
    fun fractions() {
        assertEquals("1 1/4", f(1.25, "# ?/?"))
        assertEquals("3", f(3.0, "# ?/?"))
        assertEquals("0", f(0.0, "# ?/?"))
        assertEquals("1 3/8", f(1.375, "# ?/8"))
        assertEquals("5/4", f(1.25, "?/?"))
        assertEquals("3 14/99", f(3.14159, "# ??/??").replace("  ", " "))
    }

    @Test
    fun dates() {
        assertEquals("1/15/2024", ExcelNumberFormat.format(45306.0, 14, null).text)
        assertEquals("15-Jan-24", ExcelNumberFormat.format(45306.0, 15, null).text)
        assertEquals("2024-01-15", f(45306.0, "yyyy-mm-dd"))
        assertEquals("15.01.2024 13:30", f(45306.5625, "dd.mm.yyyy hh:mm"))
        assertEquals("1:30 PM", f(45306.5625, "h:mm AM/PM"))
        assertEquals("13:30:00", f(45306.5625, "hh:mm:ss"))
        assertEquals("Monday, January 15, 2024", f(45306.0, "dddd, mmmm d, yyyy"))
        assertEquals("15 января 2024", f(45306.0, "[\$-419]d mmmm yyyy"))
        assertEquals("Январь 2024", f(45306.0, "[\$-419]mmmm yyyy"))
        assertEquals("J", f(45306.0, "mmmmm"))
        assertEquals("36:00:00", f(1.5, "[h]:mm:ss"))
        assertEquals("02:03.5", f((2 * 60 + 3.5) / 86400.0, "mm:ss.0"))
        assertEquals("2/29/1900", f(60.0, "m/d/yyyy"))
        assertEquals("3/1/1900", f(61.0, "m/d/yyyy"))
        assertEquals("1/1/1904", ExcelNumberFormat.formatCode(0.0, "m/d/yyyy", true).text)
        assertTrue(ExcelNumberFormat.isDateTime(14, null))
        assertTrue(ExcelNumberFormat.isDateTime(164, "dd/mm/yy"))
        assertFalse(ExcelNumberFormat.isDateTime(164, "#,##0.00"))
        assertFalse(ExcelNumberFormat.isDateTime(164, "0.00\"h\""))
        assertEquals("########", f(-1.0, "yyyy-mm-dd"))
        ExcelNumberFormat.systemLocale = Locale.forLanguageTag("ru-RU")
        assertEquals("15.01.2024", ExcelNumberFormat.format(45306.0, 14, null).text)
    }

    @Test
    fun text() {
        assertEquals("abc", ExcelNumberFormat.formatText("abc", 0, null).text)
        assertEquals("[abc]", ExcelNumberFormat.formatText("abc", 200, "0;-0;0;\"[\"@\"]\"").text)
        assertTrue(ExcelNumberFormat.isText(49, null))
    }
}
