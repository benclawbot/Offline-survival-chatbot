package com.localchat.data.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM harness for [PdfIngestor]. We feed in fake per-page text layers and assert
 * the plan splits pages into "read as text" vs "rasterize for vision" exactly as the
 * ingestion contract demands — text headers, the vision cap, and the vision toggle.
 *
 * Run: `./gradlew :app:testDebugUnitTest --tests com.localchat.data.documents.PdfIngestorTest`
 */
class PdfIngestorTest {

    /** Minimal in-memory [PdfDocument]: text per page, plus which pages carry a real figure. */
    private class FakePdfDocument(
        val pages: List<String>,
        val imagePages: Set<Int> = emptySet(),
    ) : PdfDocument {
        override val pageCount: Int = pages.size
        override fun pageText(index: Int): String = pages[index]
        override fun pageHasSignificantImage(index: Int): Boolean = index in imagePages
    }

    /** A line comfortably above the default 40-char text threshold. */
    private fun realText(label: String): String =
        "$label — this is a genuine extracted text layer with plenty of characters."

    @Test
    fun `all text pages produce blocks, no vision`() {
        val doc = FakePdfDocument(listOf(realText("one"), realText("two"), realText("three")))
        val plan = PdfIngestor.plan(doc, PdfIngestOptions(visionAvailable = true))

        assertTrue(plan.pagesToRender.isEmpty())
        assertFalse(plan.usedVisionFallback)
        // One block per page, in order, with 1-based headers.
        assertTrue(plan.combinedText.contains("[PDF page 1]"))
        assertTrue(plan.combinedText.contains("[PDF page 2]"))
        assertTrue(plan.combinedText.contains("[PDF page 3]"))
        assertEquals(2, Regex("\n\n").findAll(plan.combinedText).count()) // 3 blocks → 2 separators
    }

    @Test
    fun `mixed pages route blanks to vision and text to combinedText`() {
        val doc = FakePdfDocument(
            listOf(
                realText("page one"), // text
                "",                   // scanned → vision
                realText("page three"), // text
                "  \n ",               // whitespace → vision
            )
        )
        val plan = PdfIngestor.plan(doc, PdfIngestOptions(visionAvailable = true))

        // Blank pages are indices 1 and 3.
        assertEquals(listOf(1, 3), plan.pagesToRender)
        assertTrue(plan.usedVisionFallback)
        // Text pages keep their original 1-based page numbers.
        assertTrue(plan.combinedText.contains("[PDF page 1]"))
        assertTrue(plan.combinedText.contains("[PDF page 3]"))
        // Blank pages contribute no headers.
        assertFalse(plan.combinedText.contains("[PDF page 2]"))
        assertFalse(plan.combinedText.contains("[PDF page 4]"))
    }

    @Test
    fun `vision unavailable yields no render pages even for scanned pages`() {
        val doc = FakePdfDocument(listOf("", "  ", realText("only text page")))
        val plan = PdfIngestor.plan(doc, PdfIngestOptions(visionAvailable = false))

        assertTrue(plan.pagesToRender.isEmpty())
        assertFalse(plan.usedVisionFallback)
        assertTrue(plan.combinedText.contains("[PDF page 3]"))
    }

    @Test
    fun `maxRenderPages caps the queue and keeps the earliest pages`() {
        val doc = FakePdfDocument(List(6) { "" }) // 6 blank pages
        val plan = PdfIngestor.plan(
            doc,
            PdfIngestOptions(visionAvailable = true, maxRenderPages = 2),
        )

        assertEquals(listOf(0, 1), plan.pagesToRender) // first two, the rest dropped
        assertTrue(plan.usedVisionFallback)
        assertTrue(plan.combinedText.isEmpty())
    }

    @Test
    fun `vision model also renders text pages that carry a real figure`() {
        // Page 2 has a text layer AND a figure → keep its text block AND render it for vision.
        val doc = FakePdfDocument(
            pages = listOf(realText("intro"), realText("chart caption"), realText("outro")),
            imagePages = setOf(1),
        )
        val plan = PdfIngestor.plan(
            doc,
            PdfIngestOptions(visionAvailable = true, renderVisualPages = true),
        )
        // Text of every page is preserved...
        assertTrue(plan.combinedText.contains("[PDF page 1]"))
        assertTrue(plan.combinedText.contains("[PDF page 2]"))
        assertTrue(plan.combinedText.contains("[PDF page 3]"))
        // ...and only the figure-bearing page is rasterized.
        assertEquals(listOf(1), plan.pagesToRender)
        assertTrue(plan.usedVisionFallback)
    }

    @Test
    fun `figure pages are NOT rendered when renderVisualPages is off`() {
        val doc = FakePdfDocument(listOf(realText("a"), realText("b")), imagePages = setOf(0, 1))
        val plan = PdfIngestor.plan(doc, PdfIngestOptions(visionAvailable = true)) // renderVisualPages defaults false
        assertTrue(plan.pagesToRender.isEmpty())
        assertFalse(plan.usedVisionFallback)
    }

    @Test
    fun `pure-text PDF with no figures sends no images even with renderVisualPages`() {
        val doc = FakePdfDocument(listOf(realText("one"), realText("two")), imagePages = emptySet())
        val plan = PdfIngestor.plan(
            doc,
            PdfIngestOptions(visionAvailable = true, renderVisualPages = true),
        )
        assertTrue("a text-only worksheet must not be rasterized", plan.pagesToRender.isEmpty())
    }

    @Test
    fun `scanned and figure pages share one capped vision budget`() {
        // index0 scanned, index1 text+figure, index2 scanned, index3 text+figure; cap = 3.
        val doc = FakePdfDocument(
            pages = listOf("", realText("fig A"), "  ", realText("fig B")),
            imagePages = setOf(1, 3),
        )
        val plan = PdfIngestor.plan(
            doc,
            PdfIngestOptions(visionAvailable = true, renderVisualPages = true, maxRenderPages = 3),
        )
        // In page order: 0 (scanned), 1 (figure), 2 (scanned) → 4th candidate (3, figure) dropped by cap.
        assertEquals(listOf(0, 1, 2), plan.pagesToRender)
    }

    @Test
    fun `short vision doc renders ALL pages and keeps all text`() {
        // A 3-page worksheet on a vision model with renderAllUpTo=5 → every page rasterized,
        // even pure-text pages with no figures, so the model reads the whole layout.
        val doc = FakePdfDocument(listOf(realText("p1"), realText("p2"), realText("p3")))
        val plan = PdfIngestor.plan(doc, PdfIngestOptions(visionAvailable = true, renderAllUpTo = 5))
        assertEquals(listOf(0, 1, 2), plan.pagesToRender)
        assertTrue(plan.usedVisionFallback)
        assertTrue(plan.combinedText.contains("[PDF page 1]"))
        assertTrue(plan.combinedText.contains("[PDF page 3]"))
    }

    @Test
    fun `doc longer than renderAllUpTo falls back to figure and scanned selection`() {
        // 7 pages > renderAllUpTo(5): only the figure page (index 2) and scanned page (index 5) render.
        val pages = (0 until 7).map { if (it == 5) "" else realText("p$it") }
        val doc = FakePdfDocument(pages, imagePages = setOf(2))
        val plan = PdfIngestor.plan(
            doc,
            PdfIngestOptions(visionAvailable = true, renderAllUpTo = 5, renderVisualPages = true),
        )
        assertEquals(listOf(2, 5), plan.pagesToRender)
        // All text pages still contribute text.
        assertTrue(plan.combinedText.contains("[PDF page 1]"))
        assertTrue(plan.combinedText.contains("[PDF page 7]"))
    }

    @Test
    fun `renderAllUpTo disabled (0) keeps legacy behavior`() {
        val doc = FakePdfDocument(listOf(realText("a"), realText("b")))
        val plan = PdfIngestor.plan(doc, PdfIngestOptions(visionAvailable = true)) // renderAllUpTo defaults 0
        assertTrue(plan.pagesToRender.isEmpty())
    }

    @Test
    fun `empty document produces an empty plan without crashing`() {
        val plan = PdfIngestor.plan(FakePdfDocument(emptyList()), PdfIngestOptions(visionAvailable = true))

        assertEquals("", plan.combinedText)
        assertTrue(plan.pagesToRender.isEmpty())
        assertFalse(plan.usedVisionFallback)
    }

    @Test
    fun `page exactly at the min boundary counts as text`() {
        val min = 40
        val exact = "x".repeat(min) // trimmed length == minTextCharsPerPage
        val under = "y".repeat(min - 1)
        val doc = FakePdfDocument(listOf(exact, under))
        val plan = PdfIngestor.plan(
            doc,
            PdfIngestOptions(visionAvailable = true, minTextCharsPerPage = min),
        )

        // Boundary page is text; the one-char-shorter page falls to vision.
        assertTrue(plan.combinedText.contains("[PDF page 1]"))
        assertTrue(plan.combinedText.contains(exact))
        assertEquals(listOf(1), plan.pagesToRender)
    }

    @Test
    fun `combinedText uses 1-based PDF page headers and joins with blank lines`() {
        val doc = FakePdfDocument(listOf(realText("alpha"), realText("beta")))
        val plan = PdfIngestor.plan(doc, PdfIngestOptions(visionAvailable = false))

        val expected = buildString {
            append("[PDF page 1]\n").append(realText("alpha"))
            append("\n\n")
            append("[PDF page 2]\n").append(realText("beta"))
        }
        assertEquals(expected, plan.combinedText)
    }
}
