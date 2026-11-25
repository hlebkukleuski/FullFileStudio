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
import java.io.File
import me.hlebkukleuski.fullfilestudio.core.document.OfficeFonts
import me.hlebkukleuski.fullfilestudio.core.document.Ole2CfbContainer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class WordDocEngineTest {
    private val dir = File("src/test/resources/doc")

    @Before
    fun fonts() {
        OfficeFonts.loader = { name -> File("src/main/assets/fonts/office/$name").takeIf { it.isFile }?.let { Typeface.createFromFile(it) } }
        OfficeFonts.bytesLoader = { name -> File("src/main/assets/fonts/office/$name").takeIf { it.isFile }?.readBytes() }
        OfficeFonts.clearCache()
        WordFontMetrics.clearCache()
        WordFonts.clear()
    }

    private fun streams(name: String): Triple<ByteArray, ByteArray?, ByteArray?> {
        val cfb = Ole2CfbContainer(File(dir, "$name.doc").readBytes())
        val word = cfb.readStream("WordDocument") ?: error("no WordDocument in $name")
        return Triple(word, cfb.readStream(WordPagedDocument.tableStreamName(word)), cfb.readStream("Data"))
    }

    private fun load(name: String): WDocument {
        val (w, t, d) = streams(name)
        return DocReader(w, t, d).read()
    }

    private fun texts(blocks: List<WBlock>, out: MutableList<String> = ArrayList()): MutableList<String> {
        for (b in blocks) when (b) {
            is WParagraph -> out.add(b.inlines.filterIsInstance<WInline.Text>().joinToString("") { it.text })
            is WTable -> b.rows.forEach { r -> r.cells.forEach { texts(it.blocks, out) } }
        }
        return out
    }

    private fun paragraphs(blocks: List<WBlock>, out: MutableList<WParagraph> = ArrayList()): MutableList<WParagraph> {
        for (b in blocks) when (b) {
            is WParagraph -> out.add(b)
            is WTable -> b.rows.forEach { r -> r.cells.forEach { paragraphs(it.blocks, out) } }
        }
        return out
    }

    @Test
    fun listsCarryLabelsAndIndents() {
        val doc = load("Lists")
        val paras = paragraphs(doc.sections.flatMap { it.blocks })
        fun label(text: String) = paras.first { p -> p.inlines.filterIsInstance<WInline.Text>().joinToString("") { it.text } == text }
        assertEquals("•", label("Unordered list 1").listLabel)
        assertEquals("1.", label("Ordered list 1").listLabel)
        assertEquals("2.", label("OL 2").listLabel)
        assertEquals("3.", label("OL 3").listLabel)
        assertEquals("✓", label("Tick 1").listLabel)
        assertEquals("❖", label("ML 1:1").listLabel)
        assertEquals("➢", label("ML 2:1").listLabel)
        assertEquals(720, label("ML 2:1").props.indLeft)
        assertEquals(-360, label("ML 2:1").props.indFirst)
        assertEquals(1800, label("ML 5:1").props.indLeft)
        assertEquals(2, WordLayout(doc).layout().size)
    }

    @Test
    fun simpleTableHasRowsAndCells() {
        val doc = load("simple-table")
        val table = doc.sections[0].blocks.filterIsInstance<WTable>().single()
        assertEquals(2, table.rows.size)
        assertEquals(3, table.rows[0].cells.size)
        assertEquals("Cell 1,1", texts(table.rows[0].cells[0].blocks).first())
        assertEquals("Cell 2,3", texts(table.rows[1].cells[2].blocks).first())
        assertTrue(texts(doc.sections[0].blocks).contains("This text is below the table."))
    }

    @Test
    fun mergedAndNestedTables() {
        val merges = load("table-merges").sections[0].blocks.filterIsInstance<WTable>().single()
        assertEquals(4, merges.rows.size)
        assertTrue(merges.rows[1].props.cellFlags[0] and 0x40 != 0)
        assertTrue(merges.rows[2].props.cellFlags[0] and 0x20 != 0)
        val outer = load("innertable").sections[0].blocks.filterIsInstance<WTable>().single()
        val inner = outer.rows[1].cells[1].blocks.filterIsInstance<WTable>().single()
        assertEquals(2, inner.rows.size)
        assertEquals("1", texts(inner.rows[0].cells[0].blocks).first())
    }

    @Test
    fun footnotesAndEndnotesAreLaidOut() {
        val doc = load("footnote")
        assertEquals(1, doc.footnotes.size)
        assertEquals(1, doc.endnotes.size)
        val page = WordLayout(doc).layout().single()
        val text = page.text.toString()
        assertTrue(text.contains("TestFootnote"))
        assertTrue(text.contains("TestEndnote"))
        val endnote = WordLayout(load("endingnote")).layout().single().text.toString()
        assertTrue(endnote.contains("Ending note text"))
    }

    @Test
    fun firstPageHeaderDiffersFromOtherPages() {
        val doc = load("DiffFirstPageHeadFoot")
        val s = doc.sections[0]
        assertTrue(s.props.titlePage)
        assertTrue(texts(s.headers.firstHeader!!).first().startsWith("I am the header on the first page"))
        assertTrue(texts(s.headers.oddHeader!!).first().startsWith("First header column!"))
        val pages = WordLayout(doc).layout()
        assertEquals(2, pages.size)
        fun backText(p: LPage) = p.back.filterIsInstance<LItem.Text>().joinToString("") { it.text }
        assertTrue(backText(pages[0]).contains("first page"))
        assertTrue(backText(pages[1]).contains("Footer Middle"))
    }

    @Test
    fun inlinePicturesDecode() {
        val doc = load("two_images")
        val pics = doc.sections[0].blocks.filterIsInstance<WParagraph>().flatMap { it.inlines }.filterIsInstance<WInline.Picture>()
        assertEquals(2, pics.size)
        for (p in pics) {
            assertEquals(400, p.image.widthTw)
            val bmp = p.image.bitmap()
            assertNotNull(bmp)
            assertTrue(bmp!!.width > 0 && bmp.height > 0)
        }
    }

    @Test
    fun paragraphAndCharacterBorders() {
        val doc = load("Bug49919")
        val paras = paragraphs(doc.sections[0].blocks)
        val bordered = paras.first { texts(listOf(it)).first() == "Paragraph with red border" }
        assertTrue(bordered.props.borders.take(4).all { it?.visible == true })
        val words = paras.first { texts(listOf(it)).first().startsWith("Paragraph with bordered") }
        assertTrue(words.inlines.filterIsInstance<WInline.Text>().any { it.props.border?.visible == true })
    }

    @Test
    fun embeddedObjectStoragesDoNotShadowMainStreams() {
        val doc = load("word_with_embeded")
        assertEquals("I have lots of embedded files in me", texts(doc.sections[0].blocks).first())
        val pics = doc.sections[0].blocks.filterIsInstance<WParagraph>().flatMap { it.inlines }.filterIsInstance<WInline.Picture>()
        assertEquals(4, pics.size)
    }

    @Test
    fun pagedDocumentRendersWhitePageWithInk() {
        val (w, t, d) = streams("simple-table")
        val paged = WordPagedDocument.open(w, t, d)!!
        assertEquals(1, paged.pageCount)
        assertEquals(612f / 792f, paged.aspectRatio(0), 0.001f)
        assertTrue(paged.pageText(0).contains("Cell 1,1"))
        assertEquals(0, paged.search("below the table", 0))
        val bmp = Bitmap.createBitmap(306, 396, Bitmap.Config.ARGB_8888)
        paged.render(0, 0, 0, 1000, bmp)
        var white = 0
        var ink = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (c == Color.WHITE) white++ else if (Color.alpha(c) == 255) ink++
        }
        assertTrue("white=$white", white > 100000)
        assertTrue("ink=$ink", ink > 500)
        paged.release()
    }

    private class RefLine(val page: Int, val bottom: Float, val x: Float, val text: String)

    private fun reference(): Map<String, Pair<Int, List<RefLine>>> {
        val out = LinkedHashMap<String, Pair<Int, List<RefLine>>>()
        var name: String? = null
        var pages = 0
        val lines = ArrayList<RefLine>()
        fun flush() { name?.let { out[it] = pages to ArrayList(lines) } }
        for (l in File(dir, "word-reference-lines.txt").readLines()) {
            when {
                l.startsWith("DOC ") -> { flush(); name = l.substring(4); pages = 0; lines.clear() }
                l.startsWith("PAGE ") -> pages++
                l.startsWith("L ") -> {
                    val p = l.split(' ', limit = 6)
                    if (p.size == 6) lines.add(RefLine(pages, p[4].toFloat(), p[1].toFloat(), p[5]))
                }
            }
        }
        flush()
        return out
    }

    private fun norm(s: String) = s.filter { !it.isWhitespace() }.take(12)

    @Test
    fun lineGeometryMatchesWordReference() {
        val failures = StringBuilder()
        for ((name, ref) in reference()) {
            val pages = WordLayout(load(name)).layout()
            if (pages.size != ref.first) failures.append("$name: ${pages.size} pages, Word ${ref.first}\n")
            val ours = ArrayList<Triple<Int, Float, Pair<Float, String>>>()
            for ((pi, pg) in pages.withIndex()) {
                val rows = pg.items.filterIsInstance<LItem.Text>().filter { it.text.isNotBlank() }.groupBy { Math.round(it.y * 2) / 2f }.toSortedMap()
                for ((y, its) in rows) {
                    val first = its.minByOrNull { it.x }!!
                    val lead = first.text.takeWhile { it == ' ' || it == ' ' }
                    val x = first.x + if (lead.isNotEmpty() && lead.length < first.text.length) first.style.paint.measureText(lead) else 0f
                    ours.add(Triple(pi + 1, y, x to its.sortedBy { it.x }.joinToString("") { it.text }))
                }
            }
            val used = HashSet<Int>()
            val diffs = ArrayList<Triple<Int, Int, FloatArray>>()
            for ((page, y, xt) in ours) {
                val k = norm(xt.second)
                if (k.isEmpty()) continue
                for ((i, r) in ref.second.withIndex()) {
                    if (i in used) continue
                    val rk = norm(r.text)
                    if (rk == k || (k.length >= 6 && rk.startsWith(k.take(6)))) {
                        used.add(i)
                        diffs.add(Triple(page, r.page, floatArrayOf(y - r.bottom, xt.first - r.x)))
                        break
                    }
                }
            }
            if (diffs.size * 2 < ref.second.size) failures.append("$name: matched ${diffs.size}/${ref.second.size}\n")
            val median = diffs.groupBy { it.first }.mapValues { e -> e.value.map { it.third[0] }.sorted().let { it[it.size / 2] } }
            for ((op, wp, d) in diffs) {
                if (op != wp || Math.abs(d[0] - median[op]!!) > 2.0f || Math.abs(d[1]) > 1.5f) failures.append("$name: page $op/$wp dy=${d[0] - median[op]!!} dx=${d[1]}\n")
            }
            for (pg in pages) for (it in pg.items) if (it is LItem.Text && it.text.isNotBlank()) {
                if (it.x < -1f || it.x > pg.width || it.y < 0f || it.y > pg.height) failures.append("$name: text outside page at ${it.x},${it.y}\n")
            }
        }
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test
    fun freeOpenSourceFontMetricsAreComputedDynamically() {
        val substituted = listOf(
            "Times New Roman", "Arial", "Courier New", "Calibri", "Cambria",
            "Book Antiqua", "Century Gothic", "Bookman Old Style", "Century Schoolbook",
            "Arial Narrow", "Georgia", "Garamond", "Comic Sans MS",
            "Segoe UI", "Verdana", "Tahoma", "Consolas"
        )
        for (name in substituted) {
            val metrics = WordFontMetrics.of(name)
            assertNotNull("Metrics missing for $name", metrics)
            assertTrue("Ascent positive for $name", metrics!![0] > 0.5f)
            assertTrue("Descent positive for $name", metrics[1] > 0.1f)
            assertNotNull("Typeface missing for $name", OfficeFonts.typeface(name, false, false))
        }
    }

    @Test
    fun hybBinaryTableAndAutoHyphenationLayout() {
        val alphaOff = 24
        val alphaBytes = ByteArray(26) { (it + 1).toByte() }
        val trieOff = alphaOff + 12 + 28
        val nTrie = 128
        val trie = IntArray(nTrie)
        fun pack(ch: Int, link: Int, pat: Int) = (ch and 0x1F) or ((link and 0x3FFF) shl 5) or (pat shl 19)
        trie[3] = pack(3, 30, 0)
        trie[30 + 15] = pack(15, 60, 0)
        trie[60 + 13] = pack(13, 90, 0)
        trie[90] = pack(0, 0, 1)
        val patOff = trieOff + 24 + nTrie * 4
        val patDesc = intArrayOf(0, (1 shl 26) or (0 shl 20) or 0)
        val patData = byteArrayOf(3)
        val total = patOff + 16 + patDesc.size * 4 + patData.size
        val bb = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x62AD7968).putInt(0).putInt(alphaOff).putInt(trieOff).putInt(patOff).putInt(total)
        bb.position(alphaOff)
        bb.putInt(0).putInt('a'.code).putInt('z'.code + 1).put(alphaBytes)
        bb.position(trieOff)
        bb.putInt(0).putInt(0x1F).putInt(5).putInt(0x3FFF shl 5).putInt(19).putInt(nTrie)
        for (v in trie) bb.putInt(v)
        bb.position(patOff)
        bb.putInt(0).putInt(patDesc.size).putInt(16 + patDesc.size * 4).putInt(patData.size)
        for (v in patDesc) bb.putInt(v)
        bb.put(patData)
        val fn = WordHyphenator.parseHybBytes(bb.array(), 2, 3)!!
        assertArrayEquals(intArrayOf(3), fn("computer"))

        val narrow = SectionProps().apply { pageWidth = 3600; marginLeft = 720; marginRight = 720 }
        val emptyHdr = WHeaderSet(null, null, null, null, null, null)
        val p1 = WParagraph(ParaProps(), CharProps(), listOf(WInline.Text("internationalization documentation representation", CharProps())))
        val docHyph = WDocument(listOf(WSection(narrow, listOf(p1), emptyHdr)), listOf("Times New Roman"), emptyList(), emptyList(), false, 720, "", autoHyphen = true, hotZoneTw = 100)
        val pagesHyph = WordLayout(docHyph).layout()
        assertTrue(pagesHyph[0].items.any { it is LItem.Text && it.synthetic && it.text == "-" })
        assertFalse(pagesHyph[0].text.toString().contains("-"))

        val pNo = WParagraph(ParaProps().apply { noAutoHyph = true }, CharProps(), listOf(WInline.Text("internationalization documentation representation", CharProps())))
        val docNo = WDocument(listOf(WSection(narrow, listOf(pNo), emptyHdr)), listOf("Times New Roman"), emptyList(), emptyList(), false, 720, "", autoHyphen = true, hotZoneTw = 100)
        assertFalse(WordLayout(docNo).layout()[0].items.any { it is LItem.Text && it.synthetic })
    }

    @Test
    fun horizontalRulePercentAlignmentAndComments() {
        val sp = SectionProps().apply { pageWidth = 12240; marginLeft = 1440; marginRight = 1440 }
        val emptyHdr = WHeaderSet(null, null, null, null, null, null)
        val hrCenter = WParagraph(ParaProps(), CharProps(), listOf(WInline.Picture(WImage(9072, 40, 2, 500, 1, true, 0xFF336699.toInt()) { null }, CharProps())))
        val hrRight = WParagraph(ParaProps(), CharProps(), listOf(WInline.Picture(WImage(9072, 40, 2, 250, 2, false, 0xFFA0A0A0.toInt()) { null }, CharProps())))
        val commented = WParagraph(ParaProps(), CharProps(), listOf(WInline.Text("Reviewed text", CharProps()), WInline.CommentRef(0, CharProps())))
        val doc = WDocument(
            listOf(WSection(sp, listOf(hrCenter, hrRight, commented), emptyHdr)),
            listOf("Times New Roman"),
            emptyList(),
            emptyList(),
            false,
            720,
            "",
            comments = listOf(WComment("Alice", "AL", "Check metric alignment", 0, 13, 13))
        )
        val pg = WordLayout(doc).layout().single()
        val imgs = pg.items.filterIsInstance<LItem.Image>()
        assertEquals(2, imgs.size)
        val avail = (12240 - 2880) / 20f
        assertEquals(avail * 0.5f, imgs[0].w, 0.5f)
        assertEquals(72f + avail * 0.25f, imgs[0].x, 0.5f)
        assertEquals(avail * 0.25f, imgs[1].w, 0.5f)
        assertEquals(72f + avail * 0.75f, imgs[1].x, 0.5f)
        val comments = pg.items.filterIsInstance<LItem.Comment>()
        assertEquals(1, comments.size)
        assertTrue(comments[0].x > 72f + avail)
        assertTrue(pg.text.toString().contains("Alice: Check metric alignment"))
    }

    @Test
    fun tableCellRunsJoinWordsAndTabLeaderAlignsToGrid() {
        val sp = SectionProps().apply { pageWidth = 12240; marginLeft = 1440; marginRight = 1440 }
        val emptyHdr = WHeaderSet(null, null, null, null, null, null)
        val cellP = WParagraph(ParaProps().apply { inTable = true; itap = 1 }, CharProps(), listOf(
            WInline.Text("f", CharProps()),
            WInline.Text("ü", CharProps().apply { bold = true }),
            WInline.Text("r ", CharProps()),
            WInline.Text("einschlie", CharProps()),
            WInline.Text("ß", CharProps().apply { italic = true }),
            WInline.Text("lich", CharProps())
        ))
        val tp = TableProps().apply { centers = intArrayOf(0, 4000); ensure(1) }
        val tbl = WTable(listOf(WRow(listOf(WCell(listOf(cellP))), tp)))
        val tabP = WParagraph(ParaProps().apply { tabs = arrayListOf(WTab(5000, 2, 1)) }, CharProps(), listOf(
            WInline.Text("Chapter 1\t12", CharProps())
        ))
        val hiddenP = WParagraph(ParaProps(), CharProps().apply { hidden = true }, emptyList())
        val emojiP = WParagraph(ParaProps(), CharProps(), listOf(WInline.Text("Flag \uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08 ok", CharProps())))
        val softHyphP = WParagraph(ParaProps(), CharProps(), listOf(WInline.Text("super\u00ADcali\u00ADfragilistic", CharProps())))
        val doc = WDocument(listOf(WSection(sp, listOf(tbl, tabP, hiddenP, emojiP, softHyphP), emptyHdr)), listOf("Times New Roman"), emptyList(), emptyList(), false, 720, "")
        val pg = WordLayout(doc).layout().single()
        val pageTxt = pg.text.toString()
        assertTrue("Expected joined word in: $pageTxt", pageTxt.contains("für einschließlich"))
        assertFalse(pageTxt.contains("f ü r"))
        assertTrue(pageTxt.contains("\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08"))
        assertTrue(pageTxt.contains("supercalifragilistic"))
        val dots = pg.items.filterIsInstance<LItem.Text>().first { it.text.length >= 4 && it.text.all { c -> c == '.' } }
        val cw = dots.style.paint.measureText(".".repeat(64)) / 64f
        val rel = (dots.x - 72f) / cw
        assertEquals(Math.round(rel).toFloat(), rel, 0.05f)
    }
}
