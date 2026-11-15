package com.localchat.data.search

import kotlinx.coroutines.delay
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * DuckDuckGo is the ONLY search engine (project decision). To stay robust against
 * DDG's 2025+ bot-throttling we:
 *  - send consistent, browser-like headers (a desktop Chrome UA plus Referer and
 *    Sec-Fetch-* — the UA participates in DDG's vqd formation, so it must look real);
 *  - only ever request page 1 (no `vqd` token is required for the first page);
 *  - treat HTTP 202/429/403 as throttling and retry with exponential backoff + jitter;
 *  - fall back from the rich `html` endpoint to the lighter `lite` endpoint;
 *  - throw [SearchRateLimitedException] when genuinely throttled, instead of
 *    silently returning "no results".
 *
 * All networking goes through [HttpClient] and all parsing through
 * [DuckDuckGoParser], so the retry/backoff/fallback logic here is JVM-testable.
 */
@Singleton
class DuckDuckGoSearch @Inject constructor(
    private val http: HttpClient,
) : WebSearchRouter {

    private enum class Endpoint(val base: String) {
        Html("https://html.duckduckgo.com/html/") {
            override fun parse(body: String, limit: Int) = DuckDuckGoParser.parseHtml(body, base, limit)
        },
        Lite("https://lite.duckduckgo.com/lite/") {
            override fun parse(body: String, limit: Int) = DuckDuckGoParser.parseLite(body, base, limit)
        };

        fun url(encodedQuery: String) = "$base?q=$encodedQuery"
        abstract fun parse(body: String, limit: Int): List<SearchResult>
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isEmpty()) return emptyList()

        var sawRateLimit = false
        for (endpoint in Endpoint.entries) {
            val results = attempt(endpoint, q, limit) { sawRateLimit = true }
            if (results.isNotEmpty()) return results
        }
        if (sawRateLimit) {
            throw SearchRateLimitedException("DuckDuckGo throttled the request (HTTP 202). Try again shortly.")
        }
        return emptyList()
    }

    private suspend fun attempt(
        endpoint: Endpoint,
        encodedQuery: String,
        limit: Int,
        onRateLimit: () -> Unit,
    ): List<SearchResult> {
        var attemptIndex = 0
        while (attemptIndex < MAX_ATTEMPTS) {
            val resp = http.get(endpoint.url(encodedQuery), headersFor(endpoint))
            when {
                resp.isSuccess && resp.body != null ->
                    // Success — whatever we parsed is final (don't retry on 0 results;
                    // an empty page means "no hits", not "throttled").
                    return endpoint.parse(resp.body, limit)

                resp.isRateLimited -> {
                    onRateLimit()
                    attemptIndex++
                    if (attemptIndex < MAX_ATTEMPTS) delay(backoffMs(attemptIndex))
                }

                // Network error or unexpected status: give up on this endpoint, try the next.
                else -> return emptyList()
            }
        }
        return emptyList()
    }

    private fun headersFor(endpoint: Endpoint): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,ru;q=0.8",
        "Referer" to "https://duckduckgo.com/",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "DNT" to "1",
    )

    private fun backoffMs(attemptIndex: Int): Long {
        val exp = BASE_BACKOFF_MS shl (attemptIndex - 1) // 500, 1000, 2000, …
        val jitter = Random.nextLong(0, BASE_BACKOFF_MS)
        return exp + jitter
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_BACKOFF_MS = 500L
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}
