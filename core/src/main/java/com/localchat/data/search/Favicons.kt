package com.localchat.data.search

import java.net.URI

/**
 * Pure-JVM favicon helper: turns an arbitrary URL string into a clean host and,
 * from there, a DuckDuckGo icon-service URL. No `android.*` dependencies, so the
 * whole thing is exercised from a JVM harness ([FaviconsTest]).
 */
object Favicons {

    /**
     * Extracts a clean registrable host from an arbitrary URL string, or null if it
     * isn't a usable web URL.
     *
     * Behaviour:
     *  - blank input → null
     *  - prepends "https://" when the input has no scheme, so bare "example.com/x" parses
     *  - only http/https are accepted; any other scheme (ftp, mailto, file, …) → null
     *  - the port (if any) is dropped — only the host is returned
     *  - a leading "www." is stripped and the host is lowercased
     *  - a host without a dot (e.g. "localhost") → null
     *  - garbage input never throws — it returns null
     */
    fun domainOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        // URI needs a scheme to populate the host; a bare "example.com/x" otherwise
        // parses as an opaque path. Detect a scheme as "<letters>:" at the start.
        val candidate = if (HAS_SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"

        val parsed = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }

        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        // uri.host excludes the port by construction.
        val host = parsed.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host.isEmpty() || !host.contains('.')) return null

        return host
    }

    /**
     * DuckDuckGo icon service (privacy-friendly, no API key, matches our DDG-only stance).
     * Returns the .ico URL for [domain]. [sizePx] is accepted for API symmetry — the DDG
     * ip3 endpoint ignores it.
     */
    fun iconUrl(domain: String, sizePx: Int = 64): String =
        "https://icons.duckduckgo.com/ip3/$domain.ico"

    /** Convenience: [domainOf] of [url], turned into an [iconUrl], or null if the URL is unusable. */
    fun iconUrlForPage(url: String, sizePx: Int = 64): String? =
        domainOf(url)?.let { iconUrl(it, sizePx) }

    /** Matches a leading URI scheme like "https:" / "ftp:" so we know whether to prepend one. */
    private val HAS_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
}
