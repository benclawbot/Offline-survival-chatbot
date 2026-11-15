package com.localchat.data.search

import javax.inject.Inject
import javax.inject.Singleton

data class FetchedPage(
    val url: String,
    val title: String,
    val text: String,
)

/**
 * Fetches a page and returns its main readable text. An interface so tests can
 * supply canned pages without touching the network; the real implementation
 * delegates IO to [HttpClient] and parsing to [HtmlReadability].
 */
interface WebFetcher {
    suspend fun fetch(url: String): FetchedPage?
}

@Singleton
class HttpWebFetcher @Inject constructor(
    private val http: HttpClient,
) : WebFetcher {

    override suspend fun fetch(url: String): FetchedPage? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val resp = http.get(url, HEADERS)
        if (!resp.isSuccess || resp.body == null) return null
        val type = resp.contentType.orEmpty()
        if (type.isNotEmpty() &&
            !type.contains("text/html") &&
            !type.contains("text/plain") &&
            !type.contains("xml")
        ) {
            return null
        }
        return HtmlReadability.extract(resp.body, url)
    }

    private companion object {
        val HEADERS = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9,ru;q=0.8",
        )
    }
}
