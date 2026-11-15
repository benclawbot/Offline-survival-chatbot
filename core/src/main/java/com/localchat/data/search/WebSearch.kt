package com.localchat.data.search

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

interface WebSearch {
    /**
     * @throws SearchRateLimitedException when the engine throttles us (HTTP 202/429/403)
     *   and retries are exhausted. Callers should surface this to the user/agent rather
     *   than treating it as "no results".
     */
    suspend fun search(query: String, limit: Int = 5): List<SearchResult>
}

interface WebSearchRouter : WebSearch

/** Thrown when the search engine throttles us and backoff/retries are exhausted. */
class SearchRateLimitedException(message: String) : Exception(message)
