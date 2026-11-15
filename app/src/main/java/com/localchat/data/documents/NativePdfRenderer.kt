package com.localchat.data.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Rasterizes PDF pages to bitmaps with the FRAMEWORK renderer [android.graphics.pdf.PdfRenderer].
 *
 * We deliberately do NOT use PdfBox-Android's renderer: it has a long-standing scaling bug
 * (https://github.com/TomRoush/PdfBox-Android/issues/97) that places the content in the
 * top-left quarter and leaves the rest blank — which made the vision model see "blank images".
 * The framework renderer is faithful and has no such bug. PdfBox is kept only for text
 * extraction and figure detection ([PdfBoxPdfDocument]); rendering happens here.
 *
 * [PdfRenderer] needs a SEEKABLE file descriptor, so we materialise the document bytes into a
 * private cache file and open it read-only. Heavy work — call on a background dispatcher.
 */
class NativePdfRenderer(private val context: Context) {

    /**
     * Render [indices] (0-based) to ARGB_8888 bitmaps whose longer side is ~[targetLongSidePx].
     * Pages are drawn on a white background (the renderer leaves un-painted areas transparent).
     * Returns the bitmaps in the order of [indices]; pages that fail to render are skipped.
     */
    fun renderPages(bytes: ByteArray, indices: List<Int>, targetLongSidePx: Int = 1536): List<Bitmap> {
        if (indices.isEmpty() || bytes.isEmpty()) return emptyList()
        val tmp = File.createTempFile("pdfrender_", ".pdf", context.cacheDir)
        return try {
            tmp.writeBytes(bytes)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    indices.filter { it in 0 until renderer.pageCount }
                        .mapNotNull { i -> runCatching { renderPage(renderer, i, targetLongSidePx) }.getOrNull() }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun renderPage(renderer: PdfRenderer, index: Int, targetLongSidePx: Int): Bitmap {
        renderer.openPage(index).use { page ->
            // Page dimensions are in points (1/72"). Scale so the longer side ≈ targetLongSidePx,
            // never upscaling tiny pages beyond ~2x to keep memory bounded.
            val longSidePt = maxOf(page.width, page.height).coerceAtLeast(1)
            val scale = (targetLongSidePx.toFloat() / longSidePt).coerceIn(0.5f, 4f)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // White background — PDFs assume white paper; transparent areas would confuse vision.
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }
}
