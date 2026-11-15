package com.localchat.data.documents

/**
 * Pure-Kotlin decision core for PDF ingestion: given a PDF's per-page text layer,
 * decide which pages we can read as text and which are likely scanned images that
 * need to be rasterized and handed to a vision model.
 *
 * No `android.*` here on purpose — the real implementation (PDFBox text extraction +
 * [android.graphics.pdf.PdfRenderer] rasterization) lives behind the [PdfDocument]
 * abstraction and is added separately. This object only plans; it never renders.
 */

/** Abstraction over an opened PDF. The Android-backed implementation is supplied elsewhere. */
interface PdfDocument {
    /** Number of pages in the document. */
    val pageCount: Int

    /** Raw text layer for the page at [index] (0-based). Empty/blank for scanned pages. */
    fun pageText(index: Int): String

    /**
     * Whether page [index] carries a real figure/photo/chart (a sizable embedded image), as
     * opposed to being pure text or just a tiny logo. Used so a vision model is also shown the
     * pages where the meaning is in the picture, not only the text layer. Default false keeps
     * pure-text planning (and existing fakes) unaffected.
     */
    fun pageHasSignificantImage(index: Int): Boolean = false
}

/**
 * Inputs to the ingestion decision.
 *
 * @param visionAvailable whether the active model can consume rasterized page images.
 * @param minTextCharsPerPage minimum trimmed char count for a page to count as a real
 *   text layer; below this a page is treated as scanned (a vision candidate).
 * @param maxRenderPages hard cap on how many pages we will rasterize for vision, to
 *   bound cost/latency on large scanned documents.
 */
data class PdfIngestOptions(
    val visionAvailable: Boolean,
    val minTextCharsPerPage: Int = 40,
    val maxRenderPages: Int = 4,
    /**
     * When true (and [visionAvailable]), pages that HAVE a text layer but also carry a
     * significant figure/photo are ALSO rasterized for the vision model — so charts, diagrams
     * and images aren't lost to text-only extraction. The page still contributes its text block.
     * When false (default), only scanned (low-text) pages are rendered.
     */
    val renderVisualPages: Boolean = false,
    /**
     * If [visionAvailable] and the document has at most this many pages, render EVERY page for
     * the vision model (it reads the whole document visually — the most reliable path for short
     * worksheets/forms/tables, where text extraction garbles layout). Bypasses the per-page
     * heuristics and [maxRenderPages] (the doc is small by definition). `0` disables it, leaving
     * only the figure/scanned-page selection (the default, also used by unit tests).
     */
    val renderAllUpTo: Int = 0,
)

/**
 * Result of planning ingestion.
 *
 * @param combinedText all text-layer pages joined into "[PDF page N]" blocks.
 * @param pagesToRender 0-based indices of pages to rasterize for the vision model.
 * @param usedVisionFallback true iff at least one page was queued for rendering.
 */
data class PdfIngestPlan(
    val combinedText: String,
    val pagesToRender: List<Int>,
    val usedVisionFallback: Boolean,
)

object PdfIngestor {

    /**
     * Walk every page once and classify it:
     *  - real text layer (>= [PdfIngestOptions.minTextCharsPerPage] trimmed chars) →
     *    appended to [PdfIngestPlan.combinedText] as a "[PDF page N]" block (1-based header);
     *  - otherwise (low/no text = likely scanned) → a vision candidate; rasterized only if
     *    [PdfIngestOptions.visionAvailable] and we are still under [PdfIngestOptions.maxRenderPages].
     *
     * Deterministic and side-effect free: no I/O, no clock, no randomness.
     */
    fun plan(doc: PdfDocument, options: PdfIngestOptions): PdfIngestPlan {
        val blocks = mutableListOf<String>()
        val pagesToRender = mutableListOf<Int>()

        // Short document on a vision model → render the WHOLE thing (read it like a human).
        val renderAll = options.visionAvailable &&
            options.renderAllUpTo > 0 && doc.pageCount in 1..options.renderAllUpTo

        fun visionBudgetLeft() = pagesToRender.size < options.maxRenderPages
        for (index in 0 until doc.pageCount) {
            val text = doc.pageText(index).trim()
            val hasText = text.length >= options.minTextCharsPerPage
            if (hasText) {
                // Page has a usable text layer — keep it, with a 1-based human header.
                blocks += "[PDF page ${index + 1}]\n$text"
            }
            when {
                renderAll -> pagesToRender += index // every page, cap bypassed (doc is small)
                hasText -> {
                    // Long doc: render a text page only if it carries a real figure/photo/chart,
                    // which text extraction can't convey. Text block above still stands.
                    if (options.visionAvailable && options.renderVisualPages &&
                        visionBudgetLeft() && doc.pageHasSignificantImage(index)
                    ) {
                        pagesToRender += index
                    }
                }
                options.visionAvailable && visionBudgetLeft() ->
                    pagesToRender += index // likely scanned (low/no text) — rasterize
                // No vision (or budget exhausted): a low-text page contributes nothing.
            }
        }

        return PdfIngestPlan(
            combinedText = blocks.joinToString("\n\n").trim(),
            pagesToRender = pagesToRender,
            usedVisionFallback = pagesToRender.isNotEmpty(),
        )
    }
}
