package com.localchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localchat.domain.chat.Message
import com.localchat.domain.chat.Role
import com.localchat.domain.chat.Attachment
import com.localchat.domain.model.ModelInfo
import com.localchat.domain.model.ModelState
import com.localchat.runtime.ContextWindow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ChatScreen(
    onOpenDrawer: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenAttachments: () -> Unit,
    attachmentMenuOpen: Boolean,
    onPlusBoundsChange: (Rect) -> Unit,
    onOpenModelConfig: (String) -> Unit,
    vm: ChatViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val active by vm.active.collectAsState()
    val modelStates by vm.modelStates.collectAsState()
    val messages by vm.messages.collectAsState()
    val composer by vm.composer.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val loadingModel = modelStates.firstOrNull { it is ModelState.Loading } as? ModelState.Loading
    val downloadingModel = modelStates.firstOrNull { it is ModelState.Downloading } as? ModelState.Downloading
    val downloadedCount = modelStates.count { it is ModelState.Downloaded || it is ModelState.Loaded }
    val visionSupported by vm.visionSupported.collectAsState()
    val capabilities = chatCapabilitiesFor(active?.info, visionOverride = visionSupported)
    val docStatusMap by vm.docStatus.collectAsState()
    // Documents still being extracted/embedded → show a spinner on their chip.
    val processingDocIds = docStatusMap.filterValues {
        it == ChatViewModel.DocStatus.Extracting || it == ChatViewModel.DocStatus.Embedding
    }.keys
    // Full context usage (system + tools + history + draft + images + doc RAG) — computed in the VM.
    val contextUsage by vm.contextUsage.collectAsState()
    val modelMeter = when {
        active != null -> ModelTopMeter(
            label = "${contextUsage.totalTokens}/${contextUsage.usedTokens}",
            progress = contextUsage.progress,
        )
        loadingModel != null -> ModelTopMeter(label = "загрузка", progress = null)
        downloadingModel != null -> ModelTopMeter(
            label = "скачивание ${(downloadingModel.progress * 100).roundToInt().coerceIn(0, 100)}%",
            progress = downloadingModel.progress,
        )
        else -> null
    }
    val modelName = when {
        active != null -> active?.info?.displayName.orEmpty()
        loadingModel != null -> loadingModel.info.displayName
        downloadingModel != null -> downloadingModel.info.displayName
        downloadedCount > 0 -> "Выбери модель"
        else -> "Без модели"
    }
    val modelStatus = when {
        active != null -> {
            "загружена"
        }
        loadingModel != null -> "загрузка в память"
        downloadingModel != null -> "скачивание"
        downloadedCount > 0 -> "$downloadedCount скачано"
        else -> "Скачай или добавь модель"
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // "At the bottom" = the list cannot scroll any further down. This is robust to
    // bubbles taller than the viewport (the old offset+size geometry flipped to
    // "not at bottom" the moment a streamed message exceeded the viewport, which
    // froze the autoscroll). canScrollForward is exact regardless of item heights.
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    // На отправку — фокусим низ принудительно. ChatViewModel.sendTrigger тикает
    // на каждый send().
    val sendTrigger by vm.sendTrigger.collectAsState()
    LaunchedEffect(sendTrigger) {
        if (sendTrigger > 0) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.scrollToItem(total - 1, Int.MAX_VALUE)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ChatTopBar(
            modelName = modelName,
            modelStatus = modelStatus,
            meter = modelMeter,
            hasModel = active != null,
            isLoading = loadingModel != null || downloadingModel != null,
            hasDownloadedModel = downloadedCount > 0,
            onOpenDrawer = onOpenDrawer,
            onOpenModelPicker = onOpenModelPicker,
            onOpenConfig = { active?.info?.id?.let(onOpenModelConfig) },
        )

        Column(
            Modifier.weight(1f).fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (messages.isEmpty()) {
                    EmptyState(modifier = Modifier.align(Alignment.Center))
                } else {
                    MessagesList(messages = messages, vm = vm, listState = listState)
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = !atBottom && messages.isNotEmpty(),
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 12.dp),
                ) {
                    Surface(
                        onClick = {
                            scope.launch {
                                val total = listState.layoutInfo.totalItemsCount
                                if (total > 0) listState.scrollToItem(total - 1, Int.MAX_VALUE)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 6.dp,
                        tonalElevation = 4.dp,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.ArrowDownward, "В конец",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            Composer(
                state = composer,
                onInputChange = vm::updateInput,
                onSend = vm::send,
                onStop = vm::stop,
                isStreaming = isStreaming,
                onPlusClick = onOpenAttachments,
                attachmentMenuOpen = attachmentMenuOpen,
                onPlusBoundsChange = onPlusBoundsChange,
                onRemoveAttachment = vm::removeAttachment,
                onToggleWebSearch = vm::toggleWebSearch,
                onToggleThinking = vm::toggleThinking,
                capabilities = capabilities,
                processingDocIds = processingDocIds,
                modifier = Modifier.imePadding(),
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(36.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            "Чем я могу помочь?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun MessagesList(messages: List<Message>, vm: ChatViewModel, listState: LazyListState) {
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val isUserScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    // Follow the stream until the user scrolls up; re-engage when they return to the bottom.
    var stickyEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(isUserScrolling, atBottom) {
        if (isUserScrolling && !atBottom) stickyEnabled = false
        if (atBottom) stickyEnabled = true
    }

    val lastIndex = messages.lastIndex
    // Re-pin at most once per ~64 generated chars to avoid starving touch scrolls.
    val lastSizeBucket = (messages.lastOrNull()?.text?.length ?: 0) / 64
    // Pin the BOTTOM of the last (possibly very tall) bubble: scrollToItem with a huge
    // offset clamps to max scroll, so streamed text stays glued to the bottom instead
    // of pinning the top of an oversized bubble (the old freeze bug).
    LaunchedEffect(lastIndex, lastSizeBucket) {
        if (lastIndex >= 0 && stickyEnabled) {
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(messages, key = { it.id }) { msg ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (msg.role == Role.User) Arrangement.End else Arrangement.Start,
            ) {
                MessageBubble(
                    msg = msg,
                    onEdit = { newText, attachments, regenerate ->
                        vm.editMessage(msg.id, newText, attachments, regenerate)
                    },
                    onDelete = { vm.deleteMessage(msg.id) },
                    onRegenerate = { vm.regenerateFrom(msg.id) },
                    modifier = Modifier.widthIn(max = 320.dp),
                )
            }
        }
    }
}


