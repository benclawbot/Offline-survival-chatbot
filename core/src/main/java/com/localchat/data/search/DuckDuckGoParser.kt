package com.localchat.data.search

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder

/**
 * Pure HTML → [SearchResult] parsing for DuckDuckGo's two no-JS endpoints.
 *
 * Kept free of Android and networking so it can be tested directly against HTML
 * fixtures (Jsoup is a plain JVM library). The networking, retry and backoff
 * live in [DuckDuckGoSearch].
 */
object DuckDuckGoParser {

    /** `https://html.duckduckgo.com/html/` — result blocks with explicit classes. */
    fun parseHtml(html: String, baseUrl: String, limit: Int): List<SearchResult> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.result, .web-result, .result.results_links").asSequence()
            .mapNotNull { node ->
                val a = node.selectFirst("a.result__a, a.result-link, h2 a, a[href]") ?: return@mapNotNull null
                val title = a.text().trim().ifEmpty { return@mapNotNull null }
                val href = decodeRedirect(a.attr("abs:href").ifEmpty { a.attr("href") })
                val snippet = node.selectFirst("a.result__snippet, .result__snippet, .result-snippet")
                    ?.text()?.trim().orEmpty()
                SearchResult(title = title, url = href, snippet = snippet)
            }
            .clean(limit)
    }

    /** `https://lite.duckduckgo.com/lite/` — flat table of links + sibling snippet rows. */
    fun parseLite(html: String, baseUrl: String, limit: Int): List<SearchResult> {
        val doc: Document = Jsoup.parse(html, baseUrl)
        return doc.select("a[href]").asSequence()
            .filter { it.attr("href").contains("uddg=") || it.attr("abs:href").startsWith("http") }
            .mapNotNull { a ->
                val title = a.text().trim().ifEmpty { return@mapNotNull null }
                val href = decodeRedirect(a.attr("abs:href").ifEmpty { a.attr("href") })
                val row = a.parent()?.parent()
                val snippet = row?.nextElementSibling()?.text()?.trim().orEmpty()
                SearchResult(title = title, url = href, snippet = snippet)
            }
            .clean(limit)
    }

    private fun Sequence<SearchResult>.clean(limit: Int): List<SearchResult> =
        map { it.copy(url = normalizeUrl(it.url), title = it.title.cleanText(), snippet = it.snippet.cleanText()) }
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .filterNot { result ->
                val url = result.url.lowercase()
                url.contains("duckduckgo.com/y.js") ||
                    url.contains("duckduckgo.com/l/") ||
                    url.contains("/settings") ||
                    url.contains("improve.duckduckgo")
            }
            .distinctBy { stripTracking(it.url) }
            .take(limit)
            .toList()

    /** DDG wraps outbound links as `…/l/?uddg=<percent-encoded-real-url>`. */
    private fun decodeRedirect(href: String): String {
        val marker = "uddg="
        val idx = href.indexOf(marker)
        if (idx < 0) return href
        val tail = href.substring(idx + marker.length).substringBefore('&')
        return runCatching { URLDecoder.decode(tail, "UTF-8") }.getOrDefault(tail)
    }

    private fun normalizeUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        else -> url
    }

    private fun stripTracking(url: String): String = url
        .substringBefore("#")
        .replace(Regex("[?&](utm_[^=&]+|fbclid|gclid|yclid|ref)=[^&]*"), "")

    private fun String.cleanText(): String = replace(Regex("\\s+"), " ").trim()
}
