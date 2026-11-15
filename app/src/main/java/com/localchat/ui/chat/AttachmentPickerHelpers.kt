package com.localchat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.localchat.domain.chat.Attachment
import java.io.File
import java.util.UUID

fun imageAttachmentFromUri(context: Context, uri: Uri): Attachment.Image =
    Attachment.Image(
        id = UUID.randomUUID().toString(),
        uri = uri.toString(),
        name = displayName(context, uri) ?: "image",
    )

fun documentAttachmentFromUri(context: Context, uri: Uri): Attachment.Document =
    Attachment.Document(
        id = UUID.randomUUID().toString(),
        uri = uri.toString(),
        name = displayName(context, uri) ?: "document",
        sizeBytes = sizeBytes(context, uri) ?: 0L,
    )

fun cameraImageAttachment(context: Context, bitmap: Bitmap): Attachment.Image {
    val dir = File(context.cacheDir, "camera")
    dir.mkdirs()
    val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
    file.outputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
    }
    return Attachment.Image(
        id = UUID.randomUUID().toString(),
        uri = file.absolutePath,
        name = file.name,
    )
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

private fun sizeBytes(context: Context, uri: Uri): Long? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
        }
