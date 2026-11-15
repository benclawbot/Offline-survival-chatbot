package com.localchat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

@Composable
fun AttachmentSheet(
    anchorBounds: Rect?,
    webSearch: Boolean,
    thinking: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDocument: () -> Unit,
    onVoice: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onToggleThinking: () -> Unit,
    capabilities: ChatCapabilities,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val sidePaddingPx = with(density) { 16.dp.roundToPx() }
    val menuWidthPx = with(density) { 300.dp.roundToPx() }
    val fallbackMenuHeightPx = with(density) { 320.dp.roundToPx() }
    val coverOffsetPx = with(density) { 10.dp.roundToPx() }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var menuVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { menuVisible = true }

    val popupOffset = remember(anchorBounds, menuSize, screenWidthPx, screenHeightPx, density) {
        val width = menuSize.width.takeIf { it > 0 } ?: menuWidthPx
        val height = menuSize.height.takeIf { it > 0 } ?: fallbackMenuHeightPx
        val anchor = anchorBounds
        if (anchor != null) {
            val rawX = anchor.left.roundToInt() - coverOffsetPx
            val rawY = anchor.bottom.roundToInt() - height + coverOffsetPx
            IntOffset(
                x = rawX.coerceIn(sidePaddingPx, (screenWidthPx - width - sidePaddingPx).coerceAtLeast(sidePaddingPx)),
                y = rawY.coerceIn(sidePaddingPx, (screenHeightPx - height - sidePaddingPx).coerceAtLeast(sidePaddingPx)),
            )
        } else {
            IntOffset(sidePaddingPx, (screenHeightPx - height - with(density) { 88.dp.roundToPx() }).coerceAtLeast(sidePaddingPx))
        }
    }
    BackHandler(onBack = onDismiss)

    Popup(
        alignment = Alignment.BottomStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        ) {
            AnimatedVisibility(
                visible = menuVisible,
                enter = fadeIn(tween(140)) + scaleIn(
                    animationSpec = tween(180),
                    initialScale = 0.82f,
                    transformOrigin = TransformOrigin(0.08f, 1f),
                ) + expandVertically(
                    animationSpec = tween(180),
                    expandFrom = Alignment.Bottom,
                ),
                exit = fadeOut(tween(100)) + scaleOut(
                    animationSpec = tween(140),
                    targetScale = 0.86f,
                    transformOrigin = TransformOrigin(0.08f, 1f),
                ) + shrinkVertically(
                    animationSpec = tween(140),
                    shrinkTowards = Alignment.Bottom,
                ),
                modifier = Modifier
                    .offset { popupOffset }
                    .navigationBarsPadding(),
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 10.dp,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .width(300.dp)
                        .widthIn(max = 320.dp)
                        .onGloballyPositioned { menuSize = it.size }
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {},
                ) {
                    val scrollState = rememberScrollState()
                    Box {
                        Column(
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(scrollState)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        ) {
                            if (capabilities.imageInput.available) {
                                MenuActionRow(Icons.Outlined.CameraAlt, "Camera", onCamera)
                                MenuActionRow(Icons.Outlined.Image, "Photo", onGallery)
                            }
                            if (capabilities.documentInput.available) {
                                MenuActionRow(Icons.Outlined.AttachFile, "File", onDocument)
                            }
                            if (capabilities.voiceInput.available) {
                                MenuActionRow(Icons.Outlined.Mic, "Voice", onVoice)
                            }
                            if (capabilities.webSearch.available) {
                                MenuModeRow(Icons.Outlined.Public, "Web search", webSearch, onToggleWebSearch)
                            }
                            if (capabilities.thinking.available) {
                                MenuModeRow(Icons.Outlined.Psychology, "Reasoning", thinking, onToggleThinking)
                            }
                            Box(Modifier.height(2.dp))
                        }
                        if (scrollState.maxValue > 0) {
                            ScrollIndicator(
                                value = scrollState.value,
                                maxValue = scrollState.maxValue,
                                modifier = Modifier.align(Alignment.CenterEnd)
                                    .padding(end = 6.dp, top = 18.dp, bottom = 18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollIndicator(value: Int, maxValue: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(3.dp)
            .height(260.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), RoundedCornerShape(50)),
    ) {
        val visibleFraction = (260f / (260f + maxValue)).coerceIn(0.18f, 1f)
        val thumbHeight = (260.dp * visibleFraction)
        val y = (260.dp - thumbHeight) * (value / maxValue.toFloat())
        Box(
            Modifier
                .offset(y = y)
                .fillMaxWidth()
                .height(thumbHeight)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), RoundedCornerShape(50)),
        )
    }
}

@Composable
private fun MenuActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun MenuModeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val container = if (enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
    else MaterialTheme.colorScheme.surface
    val iconContainer = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val iconTint = if (enabled) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(18.dp),
        color = container,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = iconContainer, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, label, tint = iconTint, modifier = Modifier.size(21.dp))
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
    }
}
