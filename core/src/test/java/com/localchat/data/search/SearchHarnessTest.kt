package com.localchat.data.search

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM harness for the DuckDuckGo HTML parser. */
class DuckDuckGoParserTest {

    @Test
    fun `parses html endpoint results and decodes uddg redirect`() {
        val html = """
            <html><body>
            <div class="result">
              <h2><a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FTokyo&rut=x">Tokyo — Wikipedia</a></h2>
              <a class="result__snippet">Tokyo is the capital of Japan.</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fweather.com%2Ftokyo">Tokyo Weather</a>
              <a class="result__snippet">Current conditions in Tokyo.</a>
            </div>
            </body></html>
        """.trimIndent()

        val results = DuckDuckGoParser.parseHtml(html, "https://html.duckduckgo.com/", limit = 10)

        assertEquals(2, results.size)
        assertEquals("Tokyo — Wikipedia", results[0].title)
        assertEquals("https://en.wikipedia.org/wiki/Tokyo", results[0].url)
        assertEquals("Tokyo is the capital of Japan.", results[0].snippet)
        assertEquals("https://weather.com/tokyo", results[1].url)
    }

    @Test
    fun `respects the limit`() {
        val html = (1..5).joinToString("") { i ->
            """<div class="result"><a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fsite$i.com%2F">Site $i</a></div>"""
        }
        val results = DuckDuckGoParser.parseHtml(html, "https://html.duckduckgo.com/", limit = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `dedupes results that differ only by tracking params`() {
        val html = """
            <div class="result"><a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fshop.com%2Fx%3Futm_source%3Da">A</a></div>
            <div class="result"><a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fshop.com%2Fx%3Futm_source%3Db">B</a></div>
        """.trimIndent()
        val results = DuckDuckGoParser.parseHtml(html, "https://html.duckduckgo.com/", limit = 10)
        assertEquals(1, results.size)
    }

    @Test
    fun `parses lite endpoint table with sibling snippets`() {
        val html = """
            <html><body><table>
            <tr><td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa">Example A</a></td></tr>
            <tr><td class="result-snippet">Snippet for A here.</td></tr>
            <tr><td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fb">Example B</a></td></tr>
            <tr><td class="result-snippet">Snippet for B here.</td></tr>
            </table></body></html>
        """.trimIndent()

        val results = DuckDuckGoParser.parseLite(html, "https://lite.duckduckgo.com/", limit = 10)

        assertEquals(2, results.size)
        assertEquals("https://example.org/a", results[0].url)
        assertEquals("Example A", results[0].title)
        assertTrue(results[0].snippet.contains("Snippet for A"))
    }

    @Test
    fun `garbage html yields no results`() {
        assertTrue(DuckDuckGoParser.parseHtml("<html><body>nothing here</body></html>", "https://x/", 10).isEmpty())
    }
}

/** Pure-JVM harness for readable-text extraction. */
class HtmlReadabilityTest {

    @Test
    fun `extracts main article text and strips boilerplate`() {
        val html = """
            <html><head><title>My Page</title></head><body>
            <header>site name nav</header>
            <nav>menu home about</nav>
            <article><p>Main content paragraph one.</p><p>Second paragraph.</p></article>
            <footer>copyright 2026</footer>
            <script>var x = 1;</script>
            </body></html>
        """.trimIndent()

        val page = HtmlReadability.extract(html, "https://site.com/post")!!
        assertEquals("My Page", page.title)
        assertTrue(page.text.contains("Main content paragraph one."))
        assertTrue(page.text.contains("Second paragraph."))
        assertFalse(page.text.contains("menu home about"))
        assertFalse(page.text.contains("copyright"))
        assertFalse(page.text.contains("var x"))
    }

    @Test
    fun `blank or contentless html returns null`() {
        assertNull(HtmlReadability.extract("", "https://x/"))
        assertNull(HtmlReadability.extract("<html><head></head><body></body></html>", "https://x/"))
    }
}

/** Harness for the retry / backoff / endpoint-fallback / ratelimit logic. */
class DuckDuckGoSearchTest {

    private class FakeHttpClient(val handler: (String) -> HttpResponse) : HttpClient {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requested.add(url)
            return handler(url)
        }
    }

    private fun htmlResultPage() = """
        <div class="result"><a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fa.com%2F1">A1</a></div>
    """.trimIndent()

    private fun liteResultPage() = """
        <table><tr><td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fb.com%2F1">B1</a></td></tr></table>
    """.trimIndent()

    @Test
    fun `returns results on first successful html request`() = runTest {
        val http = FakeHttpClient { HttpResponse(200, htmlResultPage(), "text/html") }
        val results = DuckDuckGoSearch(http).search("tokyo", 5)
        assertEquals(1, results.size)
        assertEquals("https://a.com/1", results[0].url)
        // lite never queried once html succeeded
        assertTrue(http.requested.none { it.contains("lite.duckduckgo") })
    }

    @Test
    fun `falls back to lite endpoint when html is rate-limited`() = runTest {
        val http = FakeHttpClient { url ->
            if (url.contains("html.duckduckgo")) HttpResponse(202, null, null)
            else HttpResponse(200, liteResultPage(), "text/html")
        }
        val results = DuckDuckGoSearch(http).search("tokyo", 5)
        assertEquals(1, results.size)
        assertEquals("https://b.com/1", results[0].url)
        // html retried up to the cap before giving up
        assertEquals(3, http.requested.count { it.contains("html.duckduckgo") })
        assertEquals(1, http.requested.count { it.contains("lite.duckduckgo") })
    }

    @Test
    fun `throws RateLimited when both endpoints stay throttled`() = runTest {
        val http = FakeHttpClient { HttpResponse(202, null, null) }
        val search = DuckDuckGoSearch(http)
        var thrown: Throwable? = null
        try {
            search.search("tokyo", 5)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("expected SearchRateLimitedException, got $thrown", thrown is SearchRateLimitedException)
        // 3 attempts per endpoint × 2 endpoints
        assertEquals(6, http.requested.size)
    }

    @Test
    fun `429 is treated as rate-limit and falls back to lite`() = runTest {
        val http = FakeHttpClient { url ->
            if (url.contains("html.duckduckgo")) HttpResponse(429, null, null)
            else HttpResponse(200, liteResultPage(), "text/html")
        }
        val results = DuckDuckGoSearch(http).search("tokyo", 5)
        assertEquals(1, results.size)
        assertEquals(3, http.requested.count { it.contains("html.duckduckgo") })
    }

    @Test
    fun `403 is treated as rate-limit and retried`() = runTest {
        val http = FakeHttpClient { HttpResponse(403, null, null) }
        val search = DuckDuckGoSearch(http)
        var thrown: Throwable? = null
        try {
            search.search("tokyo", 5)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is SearchRateLimitedException)
        assertEquals(6, http.requested.size)
    }

    @Test
    fun `non-ratelimit 4xx is not retried and yields empty`() = runTest {
        val http = FakeHttpClient { HttpResponse(404, null, null) }
        val results = DuckDuckGoSearch(http).search("tokyo", 5)
        assertTrue(results.isEmpty())
        // one attempt per endpoint, no retries, no ratelimit exception
        assertEquals(2, http.requested.size)
    }

    @Test
    fun `network errors yield empty results without throwing`() = runTest {
        val http = FakeHttpClient { HttpResponse(0, null, null) }
        val results = DuckDuckGoSearch(http).search("tokyo", 5)
        assertTrue(results.isEmpty())
        // one attempt per endpoint (network error is not retried)
        assertEquals(2, http.requested.size)
    }

    @Test
    fun `successful empty page is not treated as ratelimit`() = runTest {
        val http = FakeHttpClient { HttpResponse(200, "<html><body>no results</body></html>", "text/html") }
        val results = DuckDuckGoSearch(http).search("zzz", 5)
        assertTrue(results.isEmpty())
        // html success (empty) then lite success (empty), no retries
        assertEquals(2, http.requested.size)
    }
}
