package com.localchat.data.search

import org.jsoup.Jsoup

/**
 * Pure "readability" extraction: strip boilerplate, keep the main article text.
 * Jsoup is a plain JVM library, so this is fully JVM-testable against fixtures.
 */
object HtmlReadability {

    private const val BOILERPLATE = "script, style, noscript, iframe, header, footer, nav, aside, form, " +
        "svg, button, [role=navigation], [aria-hidden=true], .nav, .menu, .sidebar, .cookie, .ad, .ads"

    fun extract(html: String, url: String): FetchedPage? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html, url)
        doc.select(BOILERPLATE).remove()
        val title = doc.title().ifBlank { url }
        val main = doc.selectFirst("article, main, [role=main]") ?: doc.body()
        val text = main?.text().orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (text.isBlank()) null else FetchedPage(url = url, title = title, text = text)
    }
}
