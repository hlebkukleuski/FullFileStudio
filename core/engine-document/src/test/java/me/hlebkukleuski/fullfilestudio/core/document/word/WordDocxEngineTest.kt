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

package me.hlebkukleuski.fullfilestudio.core.document.word

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import me.hlebkukleuski.fullfilestudio.core.document.OfficeFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WordDocxEngineTest {
    @Before
    fun setup() {
        OfficeFonts.loader = { name -> File("src/main/assets/fonts/office/$name").takeIf { it.isFile }?.let { Typeface.createFromFile(it) } }
        OfficeFonts.bytesLoader = { name -> File("src/main/assets/fonts/office/$name").takeIf { it.isFile }?.readBytes() }
        OfficeFonts.clearCache()
        WordFontMetrics.clearCache()
        WordFonts.clear()
    }

    private fun buildZip(parts: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, data) in parts) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun utf8(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun samplePng(): ByteArray {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(40, 120, 210))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun richDocxStylesListsTablesHeadersFootnotesCommentsImagesRenderPaged() {
        val rootRels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        val docRels = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rIdSt" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
  <Relationship Id="rIdNm" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
  <Relationship Id="rIdTh" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>
  <Relationship Id="rIdSe" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>
  <Relationship Id="rIdFn" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes" Target="footnotes.xml"/>
  <Relationship Id="rIdEn" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/endnotes" Target="endnotes.xml"/>
  <Relationship Id="rIdCm" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/>
  <Relationship Id="rIdH1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
  <Relationship Id="rIdH2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header2.xml"/>
  <Relationship Id="rIdF1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
  <Relationship Id="rIdImg" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/img1.png"/>
</Relationships>"""

        val theme = """<?xml version="1.0" encoding="UTF-8"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <a:themeElements>
    <a:clrScheme name="Custom">
      <a:dk1><a:srgbClr val="111111"/></a:dk1>
      <a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>
      <a:accent1><a:srgbClr val="2E74B5"/></a:accent1>
    </a:clrScheme>
    <a:fontScheme name="Custom">
      <a:majorFont><a:latin typeface="Cambria"/></a:majorFont>
      <a:minorFont><a:latin typeface="Calibri"/></a:minorFont>
    </a:fontScheme>
  </a:themeElements>
</a:theme>"""

        val settings = """<?xml version="1.0" encoding="UTF-8"?>
<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:defaultTabStop w:val="720"/>
  <w:autoHyphenation/>
  <w:hyphenationZone w:val="360"/>
</w:settings>"""

        val styles = """<?xml version="1.0" encoding="UTF-8"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault><w:rPr><w:rFonts w:asciiTheme="minorHAnsi" w:hAnsiTheme="minorHAnsi"/><w:sz w:val="22"/></w:rPr></w:rPrDefault>
    <w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
    <w:pPr><w:keepNext/><w:spacing w:before="240" w:after="60"/></w:pPr>
    <w:rPr><w:rFonts w:asciiTheme="majorHAnsi"/><w:b/><w:color w:themeColor="accent1"/><w:sz w:val="32"/></w:rPr>
  </w:style>
  <w:style w:type="table" w:styleId="GridTbl">
    <w:name w:val="Grid Table"/>
    <w:tblPr>
      <w:tblBorders>
        <w:top w:val="single" w:sz="6" w:color="444444"/>
        <w:left w:val="single" w:sz="6" w:color="444444"/>
        <w:bottom w:val="single" w:sz="6" w:color="444444"/>
        <w:right w:val="single" w:sz="6" w:color="444444"/>
        <w:insideH w:val="single" w:sz="4" w:color="888888"/>
        <w:insideV w:val="single" w:sz="4" w:color="888888"/>
      </w:tblBorders>
    </w:tblPr>
    <w:tblStylePr w:type="firstRow">
      <w:rPr><w:b/></w:rPr>
      <w:tcPr><w:shd w:val="clear" w:fill="D9E2F3"/></w:tcPr>
    </w:tblStylePr>
  </w:style>
</w:styles>"""

        val numbering = """<?xml version="1.0" encoding="UTF-8"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:abstractNum w:abstractNumId="1">
    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
    <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
  </w:abstractNum>
  <w:num w:numId="1"><w:abstractNumId w:val="1"/></w:num>
</w:numbering>"""

        val footnotes = """<?xml version="1.0" encoding="UTF-8"?>
<w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:footnote w:type="separator" w:id="0"><w:p/></w:footnote>
  <w:footnote w:id="1"><w:p><w:r><w:footnoteRef/></w:r><w:r><w:t xml:space="preserve"> Footnote body text</w:t></w:r></w:p></w:footnote>
</w:footnotes>"""

        val endnotes = """<?xml version="1.0" encoding="UTF-8"?>
<w:endnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:endnote w:id="1"><w:p><w:r><w:endnoteRef/></w:r><w:r><w:t xml:space="preserve"> Endnote body text</w:t></w:r></w:p></w:endnote>
</w:endnotes>"""

        val comments = """<?xml version="1.0" encoding="UTF-8"?>
<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:comment w:id="0" w:author="Reviewer" w:initials="RV"><w:p><w:r><w:t>Looks accurate</w:t></w:r></w:p></w:comment>
</w:comments>"""

        val hdr1 = """<?xml version="1.0" encoding="UTF-8"?>
<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:r><w:t>First Page Header</w:t></w:r></w:p></w:hdr>"""

        val hdr2 = """<?xml version="1.0" encoding="UTF-8"?>
<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:r><w:t>Running Header</w:t></w:r></w:p></w:hdr>"""

        val ftr1 = """<?xml version="1.0" encoding="UTF-8"?>
<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:p><w:r><w:t xml:space="preserve">Page </w:t></w:r><w:fldSimple w:instr=" PAGE "/><w:r><w:t xml:space="preserve"> of </w:t></w:r><w:fldSimple w:instr=" NUMPAGES "/></w:p>
</w:ftr>"""

        val document = """<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
            xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">
  <w:body>
    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Quarterly Report</w:t></w:r><w:r><w:commentReference w:id="0"/></w:r></w:p>
    <w:p>
      <w:r><w:t>Visible </w:t></w:r>
      <w:ins w:id="1"><w:r><w:t>inserted</w:t></w:r></w:ins>
      <w:del w:id="2"><w:r><w:delText>removed</w:delText></w:r></w:del>
      <w:r><w:footnoteReference w:id="1"/></w:r>
      <w:r><w:endnoteReference w:id="1"/></w:r>
    </w:p>
    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>First item</w:t></w:r></w:p>
    <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Sub item</w:t></w:r></w:p>
    <w:p><m:oMath><m:f><m:num><m:r><m:t>a</m:t></m:r></m:num><m:den><m:r><m:t>b</m:t></m:r></m:den></m:f></m:oMath></w:p>
    <w:tbl>
      <w:tblPr><w:tblStyle w:val="GridTbl"/><w:tblLook w:firstRow="1"/></w:tblPr>
      <w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>
      <w:tr><w:trPr><w:tblHeader/></w:trPr>
        <w:tc><w:p><w:r><w:t>Metric</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>Merged summary</w:t></w:r></w:p></w:tc>
      </w:tr>
    </w:tbl>
    <w:p>
      <w:r><w:drawing><wp:inline><wp:extent cx="914400" cy="457200"/><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="rIdImg"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>
    </w:p>
    <w:p><w:r><w:br w:type="page"/><w:t>Page Two Body</w:t></w:r></w:p>
    <w:sectPr>
      <w:headerReference w:type="first" r:id="rIdH1"/>
      <w:headerReference w:type="default" r:id="rIdH2"/>
      <w:footerReference w:type="default" r:id="rIdF1"/>
      <w:pgSz w:w="12240" w:h="15840"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720"/>
      <w:titlePg/>
    </w:sectPr>
  </w:body>
</w:document>"""

        val zip = buildZip(mapOf(
            "_rels/.rels" to utf8(rootRels),
            "word/_rels/document.xml.rels" to utf8(docRels),
            "word/theme/theme1.xml" to utf8(theme),
            "word/settings.xml" to utf8(settings),
            "word/styles.xml" to utf8(styles),
            "word/numbering.xml" to utf8(numbering),
            "word/footnotes.xml" to utf8(footnotes),
            "word/endnotes.xml" to utf8(endnotes),
            "word/comments.xml" to utf8(comments),
            "word/header1.xml" to utf8(hdr1),
            "word/header2.xml" to utf8(hdr2),
            "word/footer1.xml" to utf8(ftr1),
            "word/media/img1.png" to samplePng(),
            "word/document.xml" to utf8(document)
        ))

        val doc = DocxReader(DocxReader.unzipEntries(zip)).read()
        assertNotNull(doc)
        doc!!
        assertTrue(doc.autoHyphen)
        assertEquals(1, doc.footnotes.size)
        assertEquals(1, doc.endnotes.size)
        assertEquals(1, doc.comments.size)
        val pages = WordLayout(doc).layout()
        assertEquals(2, pages.size)
        val p1 = pages[0].text.toString()
        val p2 = pages[1].text.toString()
        assertTrue(p1.contains("Quarterly Report"))
        assertTrue(p1.contains("Visible inserted"))
        assertFalse(p1.contains("removed"))
        assertTrue(p1.contains("1.") && p1.contains("First item"))
        assertTrue(p1.contains("a)") && p1.contains("Sub item"))
        assertTrue(p1.contains("(a/b)"))
        assertTrue(p1.contains("Metric") && p1.contains("Merged summary"))
        assertTrue(p1.contains("Footnote body text"))
        assertTrue(p1.contains("Reviewer: Looks accurate"))
        assertTrue(p2.contains("Page Two Body"))
        assertTrue(p2.contains("Endnote body text"))
        assertTrue(pages[0].back.filterIsInstance<LItem.Text>().joinToString("") { it.text }.contains("First Page Header"))
        assertTrue(pages[1].back.filterIsInstance<LItem.Text>().joinToString("") { it.text }.contains("Running Header"))
        assertTrue(pages[0].items.any { it is LItem.Image && it.image.bitmap() != null })

        val paged = WordPagedDocument.openDocx(zip)!!
        assertEquals(2, paged.pageCount)
        assertEquals(1, paged.search("Page Two Body", 0))
        val bmp = Bitmap.createBitmap(306, 396, Bitmap.Config.ARGB_8888)
        paged.render(0, 0, 0, 1000, bmp)
        var nonWhite = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (bmp.getPixel(x, y) != Color.WHITE) nonWhite++
        assertTrue("Expected ink on rendered DOCX page", nonWhite > 500)
        paged.release()
    }

    @Test
    fun malformedZipReturnsNullWithoutThrowing() {
        assertNull(WordPagedDocument.openDocx(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0xFF.toByte())))
    }
}
