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

import android.graphics.Color
import com.omnireader.core.engine.impl.BiffCf
import com.omnireader.core.engine.impl.CfBlock
import com.omnireader.core.engine.impl.CfCells
import com.omnireader.core.engine.impl.CfRule
import com.omnireader.core.engine.impl.CfStyle
import com.omnireader.core.engine.impl.CfValueObject
import com.omnireader.core.engine.impl.SheetCellStyle
import com.omnireader.core.engine.impl.SheetConditional
import com.omnireader.core.engine.impl.SheetConditionalFormats
import com.omnireader.core.engine.impl.SheetFill
import com.omnireader.core.engine.impl.SpreadsheetGridSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SheetConditionalFormatTest {

    private val grid = arrayOf(
        arrayOf<Any?>("Name", "Score", "Flag"),
        arrayOf<Any?>("a", 10.0, "x"),
        arrayOf<Any?>("b", 50.0, "y"),
        arrayOf<Any?>("c", 90.0, "x"),
        arrayOf<Any?>("d", -20.0, null),
        arrayOf<Any?>("a", 30.0, "#N/A")
    )

    private val cells = object : CfCells {
        override fun value(row: Int, col: Int, sheet: String?): Any? = grid.getOrNull(row)?.getOrNull(col)
    }

    private fun key(r: Int, c: Int) = SpreadsheetGridSession.packCoord(r, c)

    private fun run(vararg rules: CfRule, range: IntArray = intArrayOf(1, 5, 1, 1)): SheetConditional {
        val out = SheetConditional()
        SheetConditionalFormats.apply(listOf(CfBlock(listOf(range), rules.toList())), grid.size, 3, cells, { _, _ -> null }, false, out)
        return out
    }

    private val red = CfStyle(fill = SheetFill(Color.RED), color = Color.WHITE, bold = true)

    @Test
    fun cellIsAndPriority() {
        val out = run(
            CfRule("cellIs", 2, operator = "greaterThan", formulas = listOf("40"), style = CfStyle(fill = SheetFill(Color.GREEN), italic = true)),
            CfRule("cellIs", 1, operator = "between", formulas = listOf("80", "\$B\$3"), style = red)
        )
        assertNull(out.styles[key(1, 1)])
        val b3 = out.styles[key(2, 1)]!!
        assertEquals(Color.RED, b3.fill)
        assertTrue(b3.bold && b3.italic)
        assertEquals(Color.GREEN, out.styles[key(3, 1)]!!.fill)
        assertNull(out.styles[key(4, 1)])
    }

    @Test
    fun stopIfTrue() {
        val out = run(
            CfRule("cellIs", 1, stopIfTrue = true, operator = "greaterThan", formulas = listOf("40"), style = CfStyle(color = Color.BLUE)),
            CfRule("cellIs", 2, operator = "greaterThan", formulas = listOf("0"), style = CfStyle(fill = SheetFill(Color.YELLOW)))
        )
        assertNull(out.styles[key(3, 1)]!!.fill)
        assertEquals(Color.YELLOW, out.styles[key(1, 1)]!!.fill)
    }

    @Test
    fun expressionWithRelativeReferences() {
        assertEquals("\$A1=\"a\"", SheetConditionalFormats.shiftFormula("\$A1=\"a\"", 0, 3))
        assertEquals("C5+\$D\$1&\"B2\"", SheetConditionalFormats.shiftFormula("B2+\$D\$1&\"B2\"", 3, 1))
        val out = run(CfRule("expression", 1, formulas = listOf("AND(\$A2=\"a\",B2>20)"), style = red), range = intArrayOf(1, 5, 1, 2))
        assertNotNull(out.styles[key(5, 1)])
        assertNull(out.styles[key(5, 2)])
        assertNull(out.styles[key(1, 1)])
        val mod = run(CfRule("expression", 1, formulas = listOf("MOD(ROW(),2)=0"), style = red), range = intArrayOf(1, 5, 0, 0))
        assertNotNull(mod.styles[key(1, 0)])
        assertNull(mod.styles[key(2, 0)])
    }

    @Test
    fun textAndStatistics() {
        val dup = run(CfRule("duplicateValues", 1, style = red), range = intArrayOf(1, 5, 0, 0))
        assertNotNull(dup.styles[key(1, 0)])
        assertNull(dup.styles[key(2, 0)])
        val top = run(CfRule("top10", 1, rank = 2, style = red))
        assertEquals(setOf(key(2, 1), key(3, 1)), top.styles.keys)
        val above = run(CfRule("aboveAverage", 1, style = red))
        assertEquals(setOf(key(2, 1), key(3, 1)), above.styles.keys)
        val begins = run(CfRule("containsText", 1, text = "X", style = red), range = intArrayOf(1, 5, 2, 2))
        assertEquals(setOf(key(1, 2), key(3, 2)), begins.styles.keys)
        val errs = run(CfRule("containsErrors", 1, style = red), range = intArrayOf(1, 5, 2, 2))
        assertEquals(setOf(key(5, 2)), errs.styles.keys)
        val blanks = run(CfRule("containsBlanks", 1, style = red), range = intArrayOf(1, 5, 2, 2))
        assertEquals(setOf(key(4, 2)), blanks.styles.keys)
    }

    @Test
    fun colorScaleDataBarIcons() {
        val scale = run(CfRule("colorScale", 1, cfvos = listOf(CfValueObject("min"), CfValueObject("max")), colors = listOf(Color.WHITE, Color.rgb(0, 0, 255))))
        assertEquals(Color.WHITE, scale.styles[key(4, 1)]!!.fill)
        assertEquals(Color.rgb(0, 0, 255), scale.styles[key(3, 1)]!!.fill)
        val mid = scale.styles[key(2, 1)]!!.fill!!
        assertTrue(Color.red(mid) in 85..100)

        val bars = run(CfRule("dataBar", 1, cfvos = listOf(CfValueObject("min"), CfValueObject("max")), colors = listOf(Color.BLUE), barMinLength = 0f, barMaxLength = 1f))
        val axis = bars.bars[key(1, 1)]!!.axis
        assertEquals(20f / 110f, axis, 0.001f)
        assertEquals(1f, bars.bars[key(3, 1)]!!.end, 0.001f)
        assertEquals(0f, bars.bars[key(4, 1)]!!.start, 0.001f)
        assertEquals(Color.RED, bars.bars[key(4, 1)]!!.color)

        val icons = run(CfRule("iconSet", 1, cfvos = listOf(CfValueObject("percent", "0"), CfValueObject("percent", "33"), CfValueObject("percent", "67")), iconSet = "3Arrows", showValue = false))
        assertEquals(SheetConditionalFormats.ICON_ARROW_UP, icons.icons[key(3, 1)]!!.shape)
        assertEquals(SheetConditionalFormats.ICON_ARROW_DOWN, icons.icons[key(4, 1)]!!.shape)
        assertEquals(SheetConditionalFormats.ICON_ARROW_RIGHT, icons.icons[key(2, 1)]!!.shape)
        assertTrue(key(2, 1) in icons.hideText)
    }

    @Test
    fun sqrefAndPatterns() {
        val r = SheetConditionalFormats.parseSqref("A1:B3 \$D\$5")
        assertEquals(2, r.size)
        assertTrue(r[0].contentEquals(intArrayOf(0, 2, 0, 1)))
        assertTrue(r[1].contentEquals(intArrayOf(4, 4, 3, 3)))
        val f = SheetCellStyle.patternFill("lightGrid", Color.GREEN, null)!!
        assertEquals(Color.WHITE, f.bg)
        assertEquals(15, f.pattern)
        assertEquals(0xFF, SheetCellStyle.patternMask(15)!![0])
        assertEquals(0x88, SheetCellStyle.patternMask(15)!![1])
        assertFalse(SheetCellStyle.patternFill("solid", Color.GREEN, null)!!.pattern > 0)
    }

    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun binaryRuleFormulas() {
        val b12 = hex("4c0000000000c04176004120001e00000b")
        assertEquals("LEN(TRIM(H2))=0", BiffCf.formula12(b12, 0, b12.size, 1, 7))
        val b8 = hex("4c000000c04176004120001e00000b")
        assertEquals("LEN(TRIM(H2))=0", BiffCf.formula(b8, 0, b8.size, 1, 7))
        val isOdd = hex("62000800" + "61a501")
        assertEquals("ISODD(ROW())", BiffCf.formula12(isOdd, 0, isOdd.size, 0, 0))
        val textRule = hex("4c0000000000c01701006200412000420273001701006200" + "0b")
        assertEquals("LEFT(J2,LEN(\"b\"))=\"b\"", BiffCf.formula12(textRule, 0, textRule.size, 1, 9))
    }
}
