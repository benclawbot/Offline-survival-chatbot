package com.localchat.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM harness for the favicon/domain helper. */
class FaviconsTest {

    @Test
    fun `strips www and scheme, drops path and query`() {
        assertEquals("example.com", Favicons.domainOf("https://www.example.com/path?q=1"))
    }

    @Test
    fun `keeps multi-label subdomains`() {
        assertEquals("news.bbc.co.uk", Favicons.domainOf("http://news.bbc.co.uk/x"))
    }

    @Test
    fun `parses bare domain without a scheme`() {
        assertEquals("example.org", Favicons.domainOf("example.org"))
    }

    @Test
    fun `lowercases scheme and host`() {
        assertEquals("example.com", Favicons.domainOf("HTTPS://Example.COM"))
    }

    @Test
    fun `strips the port from the host`() {
        assertEquals("site.com", Favicons.domainOf("https://site.com:8443/x"))
    }

    @Test
    fun `blank input is null`() {
        assertNull(Favicons.domainOf(""))
    }

    @Test
    fun `garbage input is null`() {
        assertNull(Favicons.domainOf("not a url"))
    }

    @Test
    fun `non-http scheme is null`() {
        assertNull(Favicons.domainOf("ftp://x"))
    }

    @Test
    fun `iconUrl builds the duckduckgo ip3 url`() {
        assertEquals("https://icons.duckduckgo.com/ip3/example.com.ico", Favicons.iconUrl("example.com"))
    }

    @Test
    fun `iconUrlForPage resolves a full page url to its domain icon`() {
        assertEquals(
            "https://icons.duckduckgo.com/ip3/example.com.ico",
            Favicons.iconUrlForPage("https://www.example.com/a"),
        )
    }

    @Test
    fun `iconUrlForPage returns null for garbage`() {
        assertNull(Favicons.iconUrlForPage("garbage"))
    }
}
