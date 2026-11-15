package com.localchat.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.localchat.data.search.Favicons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders a website favicon (via DuckDuckGo's icon service) for a source URL,
 * falling back to [fallback] (a letter chip) when the URL has no host or the
 * icon can't be loaded/decoded. Results are cached per-domain in-process so the
 * same source never re-fetches; a failed load is cached too (no hammering).
 *
 * Domain extraction and the icon URL come from the pure, tested [Favicons].
 */
@Composable
fun FaviconImage(
    pageUrl: String,
    sizeDp: Int,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val domain = remember(pageUrl) { Favicons.domainOf(pageUrl) }
    if (domain == null) {
        fallback()
        return
    }
    var holder by remember(domain) { mutableStateOf(FaviconCache.cached(domain)) }
    LaunchedEffect(domain) {
        if (holder == null) {
            val bmp = withContext(Dispatchers.IO) { loadFavicon(domain) }
            FaviconCache.put(domain, bmp)
            holder = FaviconCache.cached(domain)
        }
    }
    val bmp = holder?.value
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier.size(sizeDp.dp).clip(RoundedCornerShape(6.dp)),
        )
    } else {
        fallback()
    }
}

private object FaviconCache {
    /** value == null means "attempted, no icon" — distinct from "not attempted". */
    class Holder(val value: ImageBitmap?)

    private val cache = ConcurrentHashMap<String, Holder>()
    fun cached(domain: String): Holder? = cache[domain]
    fun put(domain: String, bmp: ImageBitmap?) {
        cache[domain] = Holder(bmp)
    }
}

private fun loadFavicon(domain: String): ImageBitmap? = runCatching {
    val conn = (URL(Favicons.iconUrl(domain, sizePx = 64)).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LocalChat")
    }
    try {
        if (conn.responseCode !in 200..299) return null
        val bytes = conn.inputStream.use { it.readBytes() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } finally {
        conn.disconnect()
    }
}.getOrNull()
