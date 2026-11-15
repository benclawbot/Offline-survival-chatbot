package com.localchat.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localchat.domain.chat.Attachment
import com.localchat.domain.chat.Message
import com.localchat.domain.chat.Role
import com.localchat.domain.chat.ToolPart
import java.io.File
import java.net.URI
import java.util.UUID
import org.json.JSONObject

@Composable
fun MessageBubble(
    msg: Message,
    onEdit: (newText: String, attachments: List<Attachment>, regenerate: Boolean) -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tool-card сообщение рендерим отдельным компонентом — без bubble/edit/regenerate.
    msg.tool?.let { tool ->
        ToolCard(tool, modifier = modifier)
        return
    }
    val isUser = msg.role == Role.User
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 22.dp, topEnd = 22.dp,
        bottomStart = if (isUser) 22.dp else 6.dp,
        bottomEnd = if (isUser) 6.dp else 22.dp,
    )

    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var fullscreenImage by remember { mutableStateOf<String?>(null) }
    var documentNameDialog by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Column(modifier, horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (!msg.thinking.isNullOrBlank() && !isUser) {
            ThinkingBlock(msg.thinking, streaming = msg.isStreaming, thinkingMs = msg.thinkingMs, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (msg.attachments.isNotEmpty()) {
            AttachmentRow(
                items = msg.attachments,
                modifier = Modifier.padding(bottom = 4.dp),
                onImageTap = { fullscreenImage = it.uri },
                onDocumentTap = { openDocumentExternal(context, it) },
                onDocumentLongPress = { documentNameDialog = it.name },
            )
        }
        if (msg.text.isNotEmpty() || msg.isStreaming) {
            SelectionContainer {
                Surface(color = bg, shape = shape) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (msg.text.isEmpty() && msg.isStreaming) {
                            Text("▋", color = fg, style = MaterialTheme.typography.bodyLarge)
                        } else {
                            MarkdownText(
                                text = msg.text + if (msg.isStreaming) " ▋" else "",
                                color = fg,
                            )
                        }
                    }
                }
            }
        }
        if (!msg.isStreaming) {
            ActionsRow(
                msg = msg,
                isUser = isUser,
                onEditRequest = { editing = true },
                onDeleteRequest = { confirmDelete = true },
                onRegenerate = onRegenerate,
            )
        }
    }

    if (editing) {
        EditDialog(
            initialText = msg.text,
            initialAttachments = msg.attachments,
            isUser = isUser,
            onDismiss = { editing = false },
            onConfirm = { newText, atts, regenerate ->
                onEdit(newText, atts, regenerate)
                editing = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete message?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    fullscreenImage?.let { uri ->
        ImageFullscreenViewer(uri = uri, onDismiss = { fullscreenImage = null })
    }
    documentNameDialog?.let { name ->
        AlertDialog(
            onDismissRequest = { documentNameDialog = null },
            title = { Text("File") },
            text = { Text(name, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { documentNameDialog = null }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun ActionsRow(
    msg: Message,
    isUser: Boolean,
    onEditRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        msg.latencyMs?.let {
            Text(
                "⏱ ${humanLatency(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        msg.tokensPerSec?.let {
            Text(
                "%.1f tok/s".format(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(msg.text)) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Outlined.ContentCopy, "Copy",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (!isUser) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Refresh, "Retry",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.MoreHoriz, "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = { menuOpen = false; onEditRequest() },
                )
                if (!isUser) {
                    DropdownMenuItem(
                        text = { Text("Regenerate") },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = { menuOpen = false; onRegenerate() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = { menuOpen = false; onDeleteRequest() },
                )
            }
        }
    }
}

@Composable
private fun EditDialog(
    initialText: String,
    initialAttachments: List<Attachment>,
    isUser: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, List<Attachment>, Boolean) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var attachments by remember { mutableStateOf(initialAttachments) }
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            attachments = attachments + imageAttachmentFromUri(context, it)
        }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            attachments = attachments + documentAttachmentFromUri(context, it)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            Column {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
                if (attachments.isNotEmpty()) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(attachments, key = { it.id }) { att ->
                            EditAttachmentChip(att) {
                                attachments = attachments.filterNot { it.id == att.id }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AddAttachmentButton(
                        icon = Icons.Outlined.Image,
                        label = "Photo",
                        onClick = { imagePicker.launch("image/*") },
                    )
                    AddAttachmentButton(
                        icon = Icons.Outlined.AttachFile,
                        label = "File",
                        onClick = {
                            documentPicker.launch(
                                arrayOf(
                                    "text/*", "application/json", "application/pdf",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/rtf", "text/html", "application/octet-stream",
                                )
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (isUser) {
                    TextButton(
                        onClick = { onConfirm(text, attachments, true) },
                        enabled = text.isNotBlank() || attachments.isNotEmpty(),
                    ) { Text("Regenerate") }
                }
                TextButton(
                    onClick = { onConfirm(text, attachments, false) },
                    enabled = text.isNotBlank() || attachments.isNotEmpty(),
                ) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AddAttachmentButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun EditAttachmentChip(att: Attachment, onRemove: () -> Unit) {
    when (att) {
        is Attachment.Image -> Box(Modifier.size(64.dp)) {
            ImageThumb(uri = att.uri, sizeDp = 64)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Outlined.Close, "Remove", modifier = Modifier.size(12.dp))
                }
            }
        }
        is Attachment.Document -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
            modifier = Modifier.size(width = 130.dp, height = 56.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                Text(
                    att.name, maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(18.dp)) {
                    Icon(Icons.Outlined.Close, "Remove", modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, streaming: Boolean, thinkingMs: Long?, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val cleanText = remember(text) { cleanThinkingText(text) }
    val preview = remember(cleanText) { thinkingPreview(cleanText) }
    val isAgentTrace = remember(cleanText) {
        cleanText.contains("**🔎 Search:") || cleanText.contains("**📄 Читаю:") ||
            cleanText.startsWith("**Thinking.**")
    }
    val timer = thinkingMs?.takeIf { it > 0 }?.let { formatThinkDuration(it) }
    val headerLabel = when {
        streaming && isAgentTrace -> "Working…"
        streaming -> if (timer != null) "Thinking ($timer)" else "Thinking…"
        isAgentTrace -> "Assistant actions"
        timer != null -> "Thought for $timer"
        else -> "Thinking"
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = modifier,
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    headerLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (!expanded && preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            AnimatedVisibility(expanded) {
                SelectionContainer {
                    MarkdownText(
                        text = cleanText + if (streaming) " ▋" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AttachmentRow(
    items: List<Attachment>,
    modifier: Modifier = Modifier,
    onImageTap: (Attachment.Image) -> Unit,
    onDocumentTap: (Attachment.Document) -> Unit,
    onDocumentLongPress: (Attachment.Document) -> Unit,
) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { att ->
            when (att) {
                is Attachment.Image -> Box(
                    Modifier.clickable { onImageTap(att) },
                ) { ImageThumb(uri = att.uri, sizeDp = 140) }

                is Attachment.Document -> Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
                    modifier = Modifier
                        .size(width = 220.dp, height = 60.dp)
                        .combinedClickable(
                            onClick = { onDocumentTap(att) },
                            onLongClick = { onDocumentLongPress(att) },
                        ),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Description, null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                att.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (att.sizeBytes > 0) {
                                Text(
                                    humanFileSize(att.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageFullscreenViewer(uri: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            FullscreenImage(uri = uri, modifier = Modifier.fillMaxSize().padding(24.dp))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(Icons.Outlined.Close, "Close", tint = Color.White)
            }
        }
    }
}

private fun openDocumentExternal(context: Context, att: Attachment.Document) {
    openDocumentExternalImpl(context, att)
}

private fun openDocumentExternalImpl(context: Context, att: Attachment.Document) {
    val uri = runCatching { Uri.parse(att.uri) }.getOrNull() ?: return
    val mime = resolveMime(context, att.uri, att.name)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (mime != null) setDataAndType(uri, mime) else setData(uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            clipData = ClipData.newRawUri(att.name, uri)
        }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, att.name)) }
        .onFailure {
            if (it is ActivityNotFoundException) {
                Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
            }
        }
}

private fun resolveMime(context: Context, uri: String, name: String): String? {
    if (uri.startsWith("content://")) {
        runCatching { context.contentResolver.getType(Uri.parse(uri)) }.getOrNull()?.let { return it }
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

private fun cleanThinkingText(raw: String): String = raw
    .replace("<think>", "", ignoreCase = true)
    .replace("</think>", "", ignoreCase = true)
    .lineSequence()
    .filterNot { line ->
        val clean = line.trim().trim(':', '-', '#', '*')
        clean.equals("thinking process", ignoreCase = true) ||
            clean.equals("thought process", ignoreCase = true) ||
            clean.equals("reasoning", ignoreCase = true) ||
            clean.equals("chain of thought", ignoreCase = true) ||
            clean.startsWith("thinking process", ignoreCase = true)
    }
    .joinToString("\n")
    .trim()

private fun thinkingPreview(text: String): String = text
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .toList()
    .takeLast(2)
    .joinToString(" · ")
    .take(160)

private fun humanLatency(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000f)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

private fun humanFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun formatThinkDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ToolCard(tool: ToolPart, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val isResult = tool is ToolPart.Result
    val ok = (tool as? ToolPart.Result)?.ok ?: true
    val accent = when {
        !isResult -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ok -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    }
    val fg = when {
        !isResult -> MaterialTheme.colorScheme.onSurface
        ok -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    val sources = remember(tool) { (tool as? ToolPart.Result)?.payload?.let(::sourcePreviewsFromPayload).orEmpty() }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = modifier,
    ) {
        Column(
            Modifier.combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = { expanded = !expanded },
            ).padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    toolStatusLabel(tool),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = fg,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint = fg.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp),
                )
            }

            when (tool) {
                is ToolPart.Call -> {
                    val sub = remember(tool) { toolCallSubtitle(tool.argumentsJson) }
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg.copy(alpha = 0.82f),
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                is ToolPart.Result -> {
                    if (sources.isNotEmpty()) {
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            sources.take(if (expanded) 8 else 3).forEach { SourcePreviewRow(it, fg) }
                        }
                    } else if (tool.summary.isNotBlank()) {
                        Text(
                            tool.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg.copy(alpha = 0.82f),
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (expanded && sources.size > 8) {
                        Text(
                            "and ${sources.size - 8} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = fg.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePreviewRow(source: SourcePreview, fg: Color) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FaviconImage(pageUrl = source.url, sizeDp = 34) {
            // Fallback when there's no host (local files) or the icon fails to load.
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = fg.copy(alpha = 0.12f),
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        source.host.take(1).uppercase().ifBlank { "#" },
                        style = MaterialTheme.typography.labelLarge,
                        color = fg,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                source.title.ifBlank { source.host },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    source.host,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (source.snippet.isNotBlank()) {
                    Text(
                        source.snippet,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(2f),
                    )
                }
            }
        }
    }
}

private data class SourcePreview(
    val title: String,
    val url: String,
    val snippet: String,
) {
    val host: String = runCatching { URI(url).host.orEmpty().removePrefix("www.") }
        .getOrDefault("")
        .ifBlank { url.substringAfter("://", url).substringBefore('/') }
}

private fun sourcePreviewsFromPayload(payload: String): List<SourcePreview> = runCatching {
    val obj = JSONObject(payload)
    val arrays = listOfNotNull(
        obj.optJSONArray("results"),
        obj.optJSONArray("sources"),
        obj.optJSONArray("more"),
        obj.optJSONArray("hits"),
    )
    buildList {
        arrays.forEach { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url").ifBlank { item.optString("doc_id") }
                val title = item.optString("title").ifBlank { item.optString("doc_title") }
                val snippet = item.optString("snippet").ifBlank { item.optString("text") }.replace(Regex("\\s+"), " ").take(140)
                if (url.isNotBlank() || title.isNotBlank()) add(SourcePreview(title, url, snippet))
            }
        }
    }.distinctBy { it.url.ifBlank { it.title } }
}.getOrDefault(emptyList())

private fun toolStatusLabel(tool: ToolPart): String = when (tool) {
    is ToolPart.Call -> when (tool.name) {
        "web_research" -> "🔎 Searching the web"
        "open_url" -> "📄 Reading page"
        "search_files" -> "🗂️ Searching files"
        else -> prettyToolName(tool.name)
    }
    is ToolPart.Result -> when (tool.name) {
        "web_research" -> if (tool.ok) "✅ Sources read" else "⚠️ Search failed"
        "open_url" -> if (tool.ok) "✅ Page read" else "⚠️ Could not open"
        "search_files" -> if (tool.ok) "✅ Found in files" else "⚠️ Not found in files"
        else -> prettyToolName(tool.name)
    }
}

private fun prettyToolName(raw: String): String = when (raw) {
    "web_research" -> "Web search"
    "open_url" -> "Open link"
    "search_files" -> "Search files"
    else -> raw
}

/** Compact "what is this tool doing" line for a tool-call card (query or URL). */
private fun toolCallSubtitle(argumentsJson: String): String = runCatching {
    val obj = JSONObject(argumentsJson)
    val query = obj.optString("query")
    val url = obj.optString("url")
    when {
        query.isNotBlank() -> "«$query»"
        url.isNotBlank() -> url
        else -> ""
    }
}.getOrDefault("")


