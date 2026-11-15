package com.localchat.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ImageThumb(uri: String, sizeDp: Int) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            decodeSampledBitmap(ctx, uri, targetPx = sizeDp * 3)?.asImageBitmap()
        }
    }

    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { img ->
            Image(
                bitmap = img,
                contentDescription = "Image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(sizeDp.dp),
            )
        } ?: Icon(
            Icons.Outlined.Image, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun FullscreenImage(uri: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            decodeSampledBitmap(ctx, uri, targetPx = 2048)?.asImageBitmap()
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bmp?.let { img ->
            Image(
                bitmap = img,
                contentDescription = "Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun decodeSampledBitmap(
    ctx: android.content.Context,
    uri: String,
    targetPx: Int,
): android.graphics.Bitmap? = runCatching {
    val bytes = readUriBytes(ctx, uri) ?: return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    var w = bounds.outWidth
    var h = bounds.outHeight
    while (w > targetPx || h > targetPx) {
        sample *= 2; w /= 2; h /= 2
    }
    BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

private fun readUriBytes(ctx: android.content.Context, uri: String): ByteArray? {
    val stream = when {
        uri.startsWith("content://") || uri.startsWith("file://") ->
            ctx.contentResolver.openInputStream(Uri.parse(uri))
        else -> File(uri).takeIf { it.exists() }?.inputStream()
    } ?: return null
    return stream.use { it.readBytes() }
}
