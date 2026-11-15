package com.localchat.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal HTTP GET abstraction. Exists so that the search/fetch logic (retry,
 * backoff, parsing) can be exercised on the JVM with a fake client — the real
 * implementation is the only thing that touches the network and Android-free
 * `HttpURLConnection`.
 */
data class HttpResponse(
    val code: Int,
    val body: String?,
    val contentType: String?,
) {
    val isSuccess: Boolean get() = code in 200..299

    /**
     * DuckDuckGo's no-JS endpoints answer bot-suspected traffic with HTTP 202
     * "Ratelimit"; 429/403 are the conventional throttling codes. Treat all as
     * "back off and retry / surface to the user", never as "no results".
     */
    val isRateLimited: Boolean get() = code == 202 || code == 429 || code == 403

    /** code <= 0 signals a transport-level failure (DNS, timeout, reset). */
    val isNetworkError: Boolean get() = code <= 0
}

interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

@Singleton
class UrlHttpClient @Inject constructor() : HttpClient {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = conn.responseCode
                val type = conn.contentType
                val body = if (code in 200..299) {
                    conn.inputStream.use { it.readCapped(MAX_BODY_BYTES, charsetOf(type)) }
                } else {
                    // Drain/close error stream so the socket can be reused; we don't need its body.
                    runCatching { conn.errorStream?.close() }
                    null
                }
                HttpResponse(code = code, body = body, contentType = type)
            } catch (_: IOException) {
                HttpResponse(code = 0, body = null, contentType = null)
            } finally {
                conn.disconnect()
            }
        }

    private fun java.io.InputStream.readCapped(maxBytes: Int, charset: Charset): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            val allowed = (maxBytes - total).coerceAtLeast(0)
            if (allowed == 0) break
            val written = minOf(n, allowed)
            out.write(buf, 0, written)
            total += written
            if (total >= maxBytes) break
        }
        return out.toByteArray().toString(charset)
    }

    /** Honour the response's declared charset (e.g. ISO-8859-1, windows-1252); default UTF-8. */
    private fun charsetOf(contentType: String?): Charset {
        val name = contentType
            ?.substringAfter("charset=", "")
            ?.substringBefore(';')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }
            ?: return Charsets.UTF_8
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 20_000
        // Pages are scanned in full by the agent; cap to keep memory bounded.
        const val MAX_BODY_BYTES = 5 * 1024 * 1024
    }
}
