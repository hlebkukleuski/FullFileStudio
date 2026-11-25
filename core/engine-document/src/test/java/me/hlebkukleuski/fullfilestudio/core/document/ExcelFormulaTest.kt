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

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExcelFormulaTest {

    private val data = mapOf<String, Any?>(
        "A1" to 10.0, "A2" to 20.0, "A3" to 30.0, "A4" to "x", "A5" to true,
        "B1" to "apple", "B2" to "banana", "B3" to "cherry", "B4" to "apple pie", "B5" to null,
        "C1" to 1.0, "C2" to 2.0, "C3" to 3.0, "C4" to 4.0, "C5" to 5.0,
        "D1" to 45306.0, "D2" to 45351.0, "E1" to FormulaError.DIV0
    )
    private val other = mapOf<String, Any?>("A1" to 100.0, "B2" to "remote")

    private val ctx = object : FormulaContext {
        override fun cell(sheet: String?, row: Int, col: Int): Any? {
            val key = ExcelFormula.colLetters(col) + (row + 1)
            return if (sheet != null && sheet.equals("My Sheet", true)) other[key] else data[key]
        }
        override fun bounds(sheet: String?): IntArray = intArrayOf(5, 5)
        override fun name(name: String): String? = if (name.equals("Rate", true)) "0.2" else null
    }

    @Before
    fun locale() { ExcelNumberFormat.systemLocale = Locale.US }

    private fun e(f: String, row: Int = 0, col: Int = 6): Any? = ExcelFormula.evaluate(f, ctx, row, col)

    private fun d(f: String, row: Int = 0): Double = e(f, row) as Double

    @Test
    fun operators() {
        assertEquals(4.0, d("=-2^2"), 0.0)
        assertEquals(14.0, d("2+3*4"), 0.0)
        assertEquals(20.0, d("(2+3)*4"), 0.0)
        assertEquals(0.5, d("50%"), 1e-12)
        assertEquals("ab1", e("\"a\"&\"b\"&1"))
        assertEquals(true, e("A1<A2"))
        assertEquals(true, e("\"a\"<\"B\""))
        assertEquals(true, e("1<\"a\""))
        assertEquals(FormulaError.DIV0, e("1/0"))
        assertEquals(FormulaError.VALUE, e("1+\"x\""))
        assertEquals(31.0, d("A1+\"21\""), 0.0)
        assertEquals(FormulaError.DIV0, e("E1+1"))
        assertEquals(0.2, d("Rate"), 0.0)
        assertEquals(FormulaError.NAME, e("NoSuchName"))
        assertEquals(100.0, d("'My Sheet'!A1"), 0.0)
        assertEquals(1.0, d("TRUE+0"), 0.0)
    }

    @Test
    fun aggregates() {
        assertEquals(60.0, d("SUM(A1:A5)"), 0.0)
        assertEquals(61.0, d("SUM(A1:A3,TRUE)"), 0.0)
        assertEquals(20.0, d("AVERAGE(A1:A5)"), 0.0)
        assertEquals(3.0, d("COUNT(A1:A5)"), 0.0)
        assertEquals(5.0, d("COUNTA(A1:A5)"), 0.0)
        assertEquals(1.0, d("COUNTBLANK(B1:B5)"), 0.0)
        assertEquals(15.0, d("SUM(C:C)"), 0.0)
        assertEquals(55.0, d("SUMPRODUCT(C1:C5,C1:C5)"), 0.0)
        assertEquals(3.0, d("SUMPRODUCT((C1:C5>2)*1)"), 0.0)
        assertEquals(9.0, d("SUM(IF(C1:C5>3,C1:C5,0))"), 0.0)
        assertEquals(3.0, d("MEDIAN(C1:C5)"), 0.0)
        assertEquals(1.5811388300841898, d("STDEV(C1:C5)"), 1e-12)
        assertEquals(4.0, d("LARGE(C1:C5,2)"), 0.0)
        assertEquals(2.0, d("SMALL(C1:C5,2)"), 0.0)
        assertEquals(2.0, d("RANK(4,C1:C5)"), 0.0)
        assertEquals(3.5, d("PERCENTILE(C1:C5,0.625)"), 1e-12)
        assertEquals(2.0, d("QUARTILE(C1:C5,1)"), 0.0)
        assertEquals(1.0, d("SLOPE(C1:C5,C1:C5)"), 1e-12)
        assertEquals(9.0, d("SUBTOTAL(9,C2:C4)"), 0.0)
    }

    @Test
    fun conditionalAggregates() {
        assertEquals(2.0, d("COUNTIF(B1:B5,\"apple*\")"), 0.0)
        assertEquals(1.0, d("COUNTIF(B1:B5,\"?anana\")"), 0.0)
        assertEquals(2.0, d("COUNTIF(A1:A5,\">10\")"), 0.0)
        assertEquals(1.0, d("COUNTIF(B1:B5,\"\")"), 0.0)
        assertEquals(4.0, d("COUNTIF(B1:B5,\"<>\")"), 0.0)
        assertEquals(50.0, d("SUMIF(A1:A5,\">=20\")"), 0.0)
        assertEquals(5.0, d("SUMIF(B1:B5,\"apple*\",C1:C5)"), 0.0)
        assertEquals(2.0, d("SUMIFS(C1:C5,B1:B5,\"*an*\",A1:A5,\">0\")"), 0.0)
        assertEquals(2.0, d("COUNTIFS(C1:C5,\">1\",C1:C5,\"<4\")"), 0.0)
        assertEquals(4.5, d("AVERAGEIFS(C1:C5,C1:C5,\">3\")"), 0.0)
        assertEquals(30.0, d("MAXIFS(A1:A3,C1:C3,\"<5\")"), 0.0)
    }

    @Test
    fun logicAndInfo() {
        assertEquals("big", e("IF(A3>25,\"big\",\"small\")"))
        assertEquals(false, e("IF(A1>25,\"big\")"))
        assertEquals("ok", e("IFERROR(1/0,\"ok\")"))
        assertEquals("two", e("IFS(A1>50,\"one\",A1>5,\"two\")"))
        assertEquals("b", e("SWITCH(2,1,\"a\",2,\"b\",\"z\")"))
        assertEquals("y", e("CHOOSE(2,\"x\",\"y\")"))
        assertEquals(true, e("AND(A1>5,B1=\"APPLE\")"))
        assertEquals(true, e("XOR(TRUE,FALSE)"))
        assertEquals(true, e("ISBLANK(B5)"))
        assertEquals(true, e("ISNUMBER(A1)"))
        assertEquals(true, e("ISERROR(E1)"))
        assertEquals(true, e("ISNA(MATCH(99,C1:C5,0))"))
        assertEquals(3.0, d("ROW(A3)"), 0.0)
        assertEquals(5.0, d("ROW()", row = 4), 0.0)
        assertEquals(7.0, d("COLUMN()"), 0.0)
        assertEquals(0.0, d("MOD(ROW(),2)", row = 1), 0.0)
    }

    @Test
    fun lookup() {
        assertEquals(2.0, d("MATCH(\"banana\",B1:B5,0)"), 0.0)
        assertEquals(3.0, d("MATCH(3.5,C1:C5)"), 0.0)
        assertEquals(2.0, d("VLOOKUP(\"banana\",B1:C3,2,FALSE)"), 0.0)
        assertEquals(3.0, d("VLOOKUP(3.7,C1:C5,1)"), 0.0)
        assertEquals(FormulaError.NA, e("VLOOKUP(\"kiwi\",B1:C3,2,FALSE)"))
        assertEquals(2.0, d("VLOOKUP(\"ban*\",B1:C3,2,0)"), 0.0)
        assertEquals("cherry", e("INDEX(B1:B5,3)"))
        assertEquals(30.0, d("INDEX(A1:C3,3,1)"), 0.0)
        assertEquals("banana", e("INDEX(B1:B5,MATCH(2,C1:C5,0))"))
        assertEquals(3.0, d("XLOOKUP(\"cherry\",B1:B3,C1:C3)"), 0.0)
        assertEquals("none", e("XLOOKUP(\"kiwi\",B1:B3,C1:C3,\"none\")"))
        assertEquals(60.0, d("SUM(OFFSET(A1,0,0,3,1))"), 0.0)
        assertEquals(20.0, d("INDIRECT(\"A\"&2)"), 0.0)
        assertEquals("\$B\$3", e("ADDRESS(3,2)"))
        assertEquals(5.0, d("ROWS(C1:C5)"), 0.0)
        assertEquals(4.0, d("LOOKUP(4.2,C1:C5)"), 0.0)
    }

    @Test
    fun text() {
        assertEquals(5.0, d("LEN(B1)"), 0.0)
        assertEquals("app", e("LEFT(B1,3)"))
        assertEquals("ple", e("RIGHT(B1,3)"))
        assertEquals("nan", e("MID(B2,3,3)"))
        assertEquals(3.0, d("SEARCH(\"N\",B2)"), 0.0)
        assertEquals(FormulaError.VALUE, e("FIND(\"N\",B2)"))
        assertEquals("bXnXnX", e("SUBSTITUTE(B2,\"a\",\"X\")"))
        assertEquals("banXna", e("SUBSTITUTE(B2,\"a\",\"X\",2)"))
        assertEquals("Hello World", e("PROPER(\"hELLO wORLD\")"))
        assertEquals("a b", e("TRIM(\"  a   b \")"))
        assertEquals("apple, banana", e("TEXTJOIN(\", \",TRUE,B1:B2,B5)"))
        assertEquals("1,234.50", e("TEXT(1234.5,\"#,##0.00\")"))
        assertEquals("2024-01-15", e("TEXT(D1,\"yyyy-mm-dd\")"))
        assertEquals(12.5, d("VALUE(\"12.5\")"), 0.0)
        assertEquals("abcabc", e("REPT(\"abc\",2)"))
        assertEquals("1,234.57", e("FIXED(1234.567)"))
        assertEquals(65.0, d("CODE(\"A\")"), 0.0)
        assertEquals("A", e("CHAR(65)"))
    }

    @Test
    fun dates() {
        assertEquals(45306.0, d("DATE(2024,1,15)"), 0.0)
        assertEquals(45323.0, d("DATE(2024,1,32)"), 0.0)
        assertEquals(2024.0, d("YEAR(D1)"), 0.0)
        assertEquals(1.0, d("MONTH(D1)"), 0.0)
        assertEquals(15.0, d("DAY(D1)"), 0.0)
        assertEquals(2.0, d("WEEKDAY(D1)"), 0.0)
        assertEquals(1.0, d("WEEKDAY(D1,2)"), 0.0)
        assertEquals(45351.0, d("EOMONTH(D1,1)"), 0.0)
        assertEquals(45337.0, d("EDATE(D1,1)"), 0.0)
        assertEquals(45.0, d("DAYS(D2,D1)"), 0.0)
        assertEquals(1.0, d("DATEDIF(D1,D2,\"M\")"), 0.0)
        assertEquals(34.0, d("NETWORKDAYS(D1,D2)"), 0.0)
        assertEquals(45313.0, d("WORKDAY(D1,5)"), 0.0)
        assertEquals(0.5625, d("TIME(13,30,0)"), 1e-12)
        assertEquals(13.0, d("HOUR(0.5625)"), 0.0)
        assertEquals(30.0, d("MINUTE(0.5625)"), 0.0)
        assertEquals(3.0, d("WEEKNUM(D1)"), 0.0)
        assertEquals(60.0, d("DATE(1900,2,29)"), 0.0)
        assertEquals(29.0, d("DAY(60)"), 0.0)
    }

    @Test
    fun mathAndFinance() {
        assertEquals(2.68, d("ROUND(2.675,2)"), 1e-12)
        assertEquals(-2.0, d("ROUNDDOWN(-2.7,0)"), 0.0)
        assertEquals(3.0, d("ROUNDUP(2.1,0)"), 0.0)
        assertEquals(1200.0, d("ROUND(1234,-2)"), 0.0)
        assertEquals(1.0, d("MOD(-3,2)"), 0.0)
        assertEquals(-1.0, d("MOD(3,-2)"), 0.0)
        assertEquals(6.0, d("CEILING(5.1,2)"), 0.0)
        assertEquals(4.0, d("FLOOR(5.1,2)"), 0.0)
        assertEquals(120.0, d("FACT(5)"), 0.0)
        assertEquals(10.0, d("COMBIN(5,2)"), 0.0)
        assertEquals(6.0, d("GCD(12,18)"), 0.0)
        assertEquals(36.0, d("LCM(12,18)"), 0.0)
        assertEquals(2.0, d("LOG(100)"), 1e-12)
        assertEquals(3.0, d("LOG(8,2)"), 1e-12)
        assertEquals(-1060.6551, d("PMT(0.05/12,120,100000)"), 1e-3)
        assertEquals(-1628.894626777442, d("FV(0.05,10,0,1000)"), 1e-6)
        assertEquals(0.089, d("ROUND(IRR({-1000,300,400,500}),3)"), 1e-9)
        assertEquals(1000.0, d("NPV(0.1,1100)"), 1e-9)
        assertEquals(FormulaError.NUM, e("SQRT(-1)"))
    }
}
