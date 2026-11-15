package com.localchat.data.documents

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable

/**
 * PDFBox-backed [PdfDocument]: ANALYSIS only — per-page text (for the pure [PdfIngestor]
 * planner) and figure detection. Page RASTERIZATION lives in [NativePdfRenderer] (the
 * framework renderer), because PdfBox-Android's renderer has a scaling bug that yields
 * blank images.
 *
 * Android + PDFBox live here so [PdfIngestor]'s decision logic stays a pure, JVM-testable
 * unit. Always use within `use { }` — it owns a native PDDocument.
 */
class PdfBoxPdfDocument private constructor(
    private val document: PDDocument,
) : PdfDocument, Closeable {

    override val pageCount: Int = document.numberOfPages

    override fun pageText(index: Int): String = runCatching {
        // Fresh stripper per page — avoids any internal position/encoding state bleeding
        // between pages if one extraction partially fails.
        PDFTextStripper().apply {
            startPage = index + 1
            endPage = index + 1
        }.getText(document).trim()
    }.getOrDefault("")

    /**
     * True if the page embeds a sizable image (figure/photo/chart) — not just text or a small
     * logo. Recurses one level into form XObjects (images are often placed via a form). Vector-
     * only graphics aren't detected (they're draw ops, not image XObjects); acceptable heuristic.
     */
    override fun pageHasSignificantImage(index: Int): Boolean = runCatching {
        resourcesHaveImage(document.getPage(index).resources, depth = 0)
    }.getOrDefault(false)

    private fun resourcesHaveImage(res: PDResources?, depth: Int): Boolean {
        if (res == null || depth > MAX_FORM_DEPTH) return false
        for (name in res.xObjectNames) {
            val xobj = runCatching { res.getXObject(name) }.getOrNull()
            when (xobj) {
                is PDImageXObject ->
                    if (xobj.width >= MIN_FIGURE_PX && xobj.height >= MIN_FIGURE_PX) return true
                is PDFormXObject ->
                    if (resourcesHaveImage(xobj.resources, depth + 1)) return true
                else -> {}
            }
        }
        return false
    }

    override fun close() {
        runCatching { document.close() }
    }

    companion object {
        /** Min pixel side for an embedded image to count as a real figure (skips icons/logos). */
        private const val MIN_FIGURE_PX = 128
        private const val MAX_FORM_DEPTH = 2

        fun from(context: Context, bytes: ByteArray): PdfBoxPdfDocument {
            PDFBoxResourceLoader.init(context.applicationContext)
            return PdfBoxPdfDocument(PDDocument.load(bytes))
        }
    }
}
