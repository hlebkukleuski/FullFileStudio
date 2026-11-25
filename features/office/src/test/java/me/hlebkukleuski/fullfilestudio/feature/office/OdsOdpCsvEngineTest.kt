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

package me.hlebkukleuski.fullfilestudio.feature.office

import java.nio.charset.StandardCharsets
import me.hlebkukleuski.fullfilestudio.core.document.SheetCellFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdsOdpCsvEngineTest {
    private fun utf8(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun odpParsesMasterGradientsRichTextListsShapesTablesAndSeparatesNotes() {
        val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
  xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
  xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
  xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
  xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
  xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
  <office:styles>
    <draw:gradient draw:name="SkyGrad" draw:style="linear" draw:start-color="#1E3A8A" draw:end-color="#60A5FA" draw:angle="900"/>
    <style:style style:name="BaseText" style:family="paragraph">
      <style:text-properties fo:color="#222222" fo:font-size="18pt"/>
    </style:style>
    <style:style style:name="MasterBg" style:family="drawing-page">
      <style:drawing-page-properties draw:fill="gradient" draw:fill-gradient-name="SkyGrad"/>
    </style:style>
    <style:style style:name="AccentBar" style:family="graphic">
      <style:graphic-properties draw:fill="solid" draw:fill-color="#F59E0B" draw:stroke="none"/>
    </style:style>
  </office:styles>
  <office:automatic-styles>
    <style:page-layout style:name="PM1">
      <style:page-layout-properties fo:page-width="33.867cm" fo:page-height="19.05cm"/>
    </style:page-layout>
  </office:automatic-styles>
  <office:master-styles>
    <style:master-page style:name="Default" style:page-layout-name="PM1" draw:style-name="MasterBg">
      <draw:rect draw:style-name="AccentBar" svg:x="0cm" svg:y="18cm" svg:width="33.867cm" svg:height="1.05cm"/>
    </style:master-page>
  </office:master-styles>
</office:document-styles>"""

        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
  xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
  xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
  xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
  xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
  xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
  xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
  xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">
  <office:automatic-styles>
    <style:style style:name="P1" style:family="paragraph" style:parent-style-name="BaseText">
      <style:paragraph-properties fo:text-align="center"/>
      <style:text-properties fo:font-weight="bold" fo:font-size="28pt" fo:color="#FFFFFF"/>
    </style:style>
    <style:style style:name="T1" style:family="text">
      <style:text-properties fo:font-style="italic" fo:color="#FFDD00"/>
    </style:style>
    <style:style style:name="CellHdr" style:family="table-cell">
      <style:table-cell-properties fo:background-color="#2563EB" fo:border="0.05cm solid #1E40AF"/>
    </style:style>
    <text:list-style style:name="L1">
      <text:list-level-style-number text:level="1" style:num-format="1" style:num-suffix="."/>
    </text:list-style>
  </office:automatic-styles>
  <office:body>
    <office:presentation>
      <draw:page draw:name="Slide1" draw:master-page-name="Default">
        <draw:frame presentation:class="title" svg:x="2cm" svg:y="1cm" svg:width="28cm" svg:height="3cm">
          <draw:text-box>
            <text:p text:style-name="P1">Quarterly <text:span text:style-name="T1">Deck</text:span> #<text:page-number/></text:p>
          </draw:text-box>
        </draw:frame>
        <draw:g draw:transform="translate(1cm 2cm)">
          <draw:ellipse svg:x="2cm" svg:y="5cm" svg:width="4cm" svg:height="4cm"/>
        </draw:g>
        <draw:frame svg:x="8cm" svg:y="5cm" svg:width="12cm" svg:height="6cm">
          <draw:text-box>
            <text:list text:style-name="L1">
              <text:list-item><text:p>Revenue up</text:p></text:list-item>
              <text:list-item><text:p>Costs down</text:p></text:list-item>
            </text:list>
          </draw:text-box>
        </draw:frame>
        <draw:frame svg:x="2cm" svg:y="12cm" svg:width="18cm" svg:height="4cm">
          <table:table>
            <table:table-column/>
            <table:table-column/>
            <table:table-row>
              <table:table-cell table:style-name="CellHdr" table:number-columns-spanned="2"><text:p>Summary</text:p></table:table-cell>
              <table:covered-table-cell/>
            </table:table-row>
          </table:table>
        </draw:frame>
        <presentation:notes>
          <draw:frame svg:x="1cm" svg:y="1cm" svg:width="10cm" svg:height="5cm">
            <draw:text-box><text:p>Secret speaker note</text:p></draw:text-box>
          </draw:frame>
        </presentation:notes>
      </draw:page>
    </office:presentation>
  </office:body>
</office:document-content>"""

        val doc = OdpEngine.parse(mapOf("styles.xml" to utf8(stylesXml), "content.xml" to utf8(contentXml)))
        assertNotNull(doc)
        doc!!
        assertEquals(33.867f, doc.pageWidthCm, 0.01f)
        assertEquals(19.05f, doc.pageHeightCm, 0.01f)
        assertEquals(1, doc.slides.size)
        val slide = doc.slides[0]
        assertNotNull(slide.backgroundGradient)
        assertEquals(1, slide.masterElements.size)
        assertEquals(0xFFF59E0B.toInt(), slide.masterElements[0].fillColor)
        assertTrue(slide.plainText.contains("Quarterly Deck"))
        assertTrue(slide.plainText.contains("Revenue up"))
        assertFalse(slide.plainText.contains("Secret speaker note"))
        assertEquals("Secret speaker note", slide.notesText)
        assertTrue(slide.elements.any { it.kind == "ellipse" && it.transformMatrix != null })
        val listEl = slide.elements.first { el -> el.paragraphs.any { it.bullet != null } }
        assertEquals("1. ", listEl.paragraphs[0].bullet)
        assertEquals("2. ", listEl.paragraphs[1].bullet)
        val tblEl = slide.elements.first { it.table != null }
        assertEquals(2, tblEl.table!!.rows[0][0].colSpan)
        assertEquals(0xFF2563EB.toInt(), tblEl.table.rows[0][0].bgColor)
    }

    @Test
    fun encryptedOdfManifestDetectedAsPasswordRequired() {
        val manifest = """<?xml version="1.0"?><manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"><manifest:file-entry manifest:full-path="content.xml"><manifest:encryption-data/></manifest:file-entry></manifest:manifest>"""
        assertTrue(OdpEngine.isEncryptedOdf(mapOf("META-INF/manifest.xml" to utf8(manifest))))
        val ods = OdsSpreadsheetSession(mapOf("META-INF/manifest.xml" to utf8(manifest), "content.xml" to ByteArray(16)))
        assertTrue(ods.getLastError()?.startsWith("PASSWORD_REQUIRED") == true)
    }

    @Test
    fun odsEvaluatesOpenFormulaSemicolonsFormatsDataStylesAndAvoidsColBloat() {
        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
  xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
  xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"
  xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
  xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
  xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:automatic-styles>
    <number:currency-style style:name="N_USD">
      <number:currency-symbol>$</number:currency-symbol>
      <number:number number:decimal-places="2" number:min-integer-digits="1" number:grouping="true"/>
    </number:currency-style>
    <number:percentage-style style:name="N_PCT">
      <number:number number:decimal-places="1" number:min-integer-digits="1"/>
      <number:text>%</number:text>
    </number:percentage-style>
    <style:style style:name="CeUsd" style:family="table-cell" style:data-style-name="N_USD"/>
    <style:style style:name="CePct" style:family="table-cell" style:data-style-name="N_PCT"/>
    <style:style style:name="ColBg" style:family="table-cell">
      <style:table-cell-properties fo:background-color="#F8FAFC"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:spreadsheet>
      <table:table table:name="Budget">
        <table:table-column table:number-columns-repeated="500" table:default-cell-style-name="ColBg"/>
        <table:table-row>
          <table:table-cell office:value-type="float" office:value="1200.5" table:style-name="CeUsd"/>
          <table:table-cell office:value-type="float" office:value="299.5" table:style-name="CeUsd"/>
          <table:table-cell table:style-name="CeUsd" table:formula="of:=SUM([.A1];[.B1])"/>
          <table:table-cell table:formula='of:=IF([.C1]&gt;1000;"High";"Low")'/>
          <table:table-cell office:value-type="percentage" office:value="0.255" table:style-name="CePct"/>
          <table:table-cell table:number-columns-repeated="400"/>
        </table:table-row>
      </table:table>
    </office:spreadsheet>
  </office:body>
</office:document-content>"""

        val ods = OdsSpreadsheetSession(mapOf("content.xml" to utf8(contentXml)))
        assertEquals(null, ods.getLastError())
        val txt = ods.getPageText(0)
        assertTrue("Expected formatted USD in $txt", txt.contains("$1,200.50") || txt.contains("$1.200,50") || txt.contains("$1\u00A0200,50"))
        assertTrue("Expected SUM formula result in $txt", txt.contains("$1,500.00") || txt.contains("$1.500,00") || txt.contains("$1\u00A0500,00"))
        assertTrue("Expected IF formula result in $txt", txt.contains("High"))
        assertTrue("Expected formatted percentage in $txt", txt.contains("25.5%") || txt.contains("25,5%"))
        assertTrue(ods.getDocumentWidth() < 20000f)
    }

    @Test
    fun csvHandlesSepDirectiveMultilineQuotesMidFieldQuoteCrLfAndAutoWidths() {
        val sepCsv = "sep=;\nProduct;Specs;Price\nMonitor;27\" UHD;\"\$1,299.00\"\nCable;\"  multi\n\"\"quoted\"\"  \";\"\$19.50\"\rMouse;Wireless;\$49.99"
        val model = CsvParser.parseBytes(utf8(sepCsv), "csv")
        assertEquals(4, model.rowCount)
        assertEquals("Product", model.text(0, 0))
        assertEquals("27\" UHD", model.text(1, 1))
        assertEquals("\$1,299.00", model.text(1, 2))
        assertEquals("  multi\n\"quoted\"  ", model.text(2, 1))
        assertEquals("Mouse", model.text(3, 0))
        assertEquals("\$49.99", model.text(3, 2))
        assertTrue((model.flags(1, 2) and SheetCellFlags.NUMBER) != 0)
        assertTrue((model.flags(3, 2) and SheetCellFlags.NUMBER) != 0)
        assertEquals(true, model.style(0, 0)?.bold)
        assertNotNull(model.colWidthsPx)
        assertEquals(3, model.colWidthsPx!!.size)
    }
}
