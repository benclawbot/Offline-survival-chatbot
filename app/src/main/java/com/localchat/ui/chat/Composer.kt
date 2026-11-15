package com.localchat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.localchat.domain.chat.Attachment

@Composable
fun Composer(
    state: ComposerState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    onPlusClick: () -> Unit,
    attachmentMenuOpen: Boolean,
    onPlusBoundsChange: (Rect) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onToggleWebSearch: () -> Unit,
    onToggleThinking: () -> Unit,
    capabilities: ChatCapabilities,
    processingDocIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val canSend = state.input.isNotBlank() || state.attachments.isNotEmpty()

    Column(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (state.attachments.isNotEmpty()) {
                    AttachmentPreviewRow(state.attachments, processingDocIds, onRemoveAttachment)
                }

                if ((state.webSearch && capabilities.webSearch.available) || (state.thinking && capabilities.thinking.available)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.webSearch && capabilities.webSearch.available) CompactModeChip(
                            Icons.Outlined.Public,
                            "Поиск",
                            true,
                            onToggleWebSearch,
                        )
                        if (state.thinking && capabilities.thinking.available) CompactModeChip(
                            Icons.Outlined.Psychology,
                            "Думать",
                            true,
                            onToggleThinking,
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlusButton(
                        open = attachmentMenuOpen,
                        onClick = onPlusClick,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            onPlusBoundsChange(coordinates.boundsInWindow())
                        },
                    )

                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                            .heightIn(min = 40.dp, max = 180.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.input.isEmpty()) {
                            Text(
                                "Сообщение…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        BasicTextField(
                            value = state.input,
                            onValueChange = onInputChange,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (isStreaming) {
                        StopButton(onStop)
                    } else {
                        if (!canSend) {
                            ComposerIcon(Icons.Outlined.Mic, "Голос") { /* mic */ }
                        }
                        SendButton(canSend, onSend)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreviewRow(
    items: List<Attachment>,
    processingDocIds: Set<String>,
    onRemove: (String) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { att ->
            when (att) {
                is Attachment.Image -> Box(
                    Modifier.size(72.dp),
                ) {
                    ImageThumb(uri = att.uri, sizeDp = 72)
                    IconButton(
                        onClick = { onRemove(att.id) },
                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        ) {
                            Icon(
                                Icons.Outlined.Close, "Убрать",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(14.dp).padding(2.dp),
                            )
                        }
                    }
                }
                is Attachment.Document -> Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                    modifier = Modifier.size(width = 140.dp, height = 64.dp),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.padding(10.dp).align(Alignment.CenterStart),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (att.id in processingDocIds) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.onSurface)
                            }
                            Text(
                                att.name, maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            onClick = { onRemove(att.id) },
                            modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close, "Убрать",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlusButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = modifier.size(40.dp),
    ) {
        IconButton(onClick = onClick) {
            if (ANIMATE_PLUS_ICON) {
                AnimatedContent(
                    targetState = open,
                    transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) },
                    label = "plus-menu-icon",
                ) { isOpen ->
                    PlusIcon(isOpen)
                }
            } else {
                PlusIcon(open)
            }
        }
    }
}

@Composable
private fun PlusIcon(open: Boolean) {
    Icon(
        Icons.Outlined.Add,
        "Прикрепить",
        tint = if (open) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    )
}

private const val ANIMATE_PLUS_ICON = false

@Composable
private fun ComposerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surface
    val fg = if (enabled) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = bg, modifier = Modifier.size(40.dp)) {
        IconButton(onClick = { if (enabled) onClick() }) {
            Icon(Icons.Filled.ArrowUpward, "Отправить", tint = fg)
        }
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(40.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.Filled.Stop, "Остановить", tint = MaterialTheme.colorScheme.onError)
        }
    }
}

@Composable
private fun CompactModeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    val fg = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onErrorContainer
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.28f)),
        onClick = onClick,
        modifier = Modifier.padding(start = 5.dp),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
            Text(
                label, style = MaterialTheme.typography.labelSmall,
                color = fg,
            )
            Icon(Icons.Outlined.Close, "Отключить", tint = fg, modifier = Modifier.size(13.dp))
        }
    }
}
