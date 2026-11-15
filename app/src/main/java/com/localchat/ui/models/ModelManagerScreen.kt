package com.localchat.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localchat.domain.model.ModelInfo
import com.localchat.domain.model.ModelKind
import com.localchat.domain.model.ModelState

private enum class Filter { All, Downloaded, Available }

@Composable
fun ModelManagerScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    vm: ModelManagerViewModel = hiltViewModel(),
) {
    val states by vm.states.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.All) }
    var configTarget by remember { mutableStateOf<ModelInfo?>(null) }

    val visible = remember(states, query, filter) {
        states.filter {
            (query.isBlank() || it.info.displayName.contains(query, ignoreCase = true)) &&
                when (filter) {
                    Filter.All -> true
                    Filter.Downloaded -> it is ModelState.Loaded || it is ModelState.Downloaded || it is ModelState.Loading
                    Filter.Available -> it is ModelState.Available || it is ModelState.Downloading || it is ModelState.Error
                }
        }
    }
    // Within a kind, show ready models first, then in-progress, then available/errored.
    fun rank(s: ModelState) = when (s) {
        is ModelState.Loaded, is ModelState.Downloaded, is ModelState.Loading -> 0
        is ModelState.Downloading -> 1
        else -> 2
    }
    val llm = visible.filter { it.info.kind == ModelKind.Llm }.sortedBy(::rank)
    val embedding = visible.filter { it.info.kind == ModelKind.Embedding }.sortedBy(::rank)

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        TopBar(onBack, onAdd)
        SearchBar(query = query, onQueryChange = { query = it })
        FilterRow(filter = filter, onFilterChange = { filter = it })

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (llm.isNotEmpty()) {
                item("h-llm") { GroupHeader("Чат-модели (${llm.size})") }
                items(llm, key = { it.info.id }) { st ->
                    ModelCard(state = st, vm = vm, onConfig = { configTarget = st.info })
                }
            }
            if (embedding.isNotEmpty()) {
                item("h-emb") { GroupHeader("Embedding-модели · семантический поиск (${embedding.size})") }
                items(embedding, key = { it.info.id }) { st ->
                    ModelCard(state = st, vm = vm, onConfig = { configTarget = st.info })
                }
            }
        }
    }

    configTarget?.let { info ->
        ConfigDialog(
            info = info,
            onDismiss = { configTarget = null },
            onConfirm = { contextSize, maxTokens, topK, topP, temperature, accelerator, enableSpeculative ->
                vm.updateConfig(info.id, contextSize, maxTokens, topK, topP, temperature, accelerator, enableSpeculative)
                configTarget = null
            },
        )
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(44.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(
            "Модели",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Surface(
            onClick = onAdd,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(44.dp),
        ) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, "Добавить", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Поиск моделей…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                BasicTextField(
                    value = query, onValueChange = onQueryChange,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FilterRow(filter: Filter, onFilterChange: (Filter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(Filter.All to "Все", Filter.Downloaded to "Скачано", Filter.Available to "Доступно").forEach { (f, label) ->
            val active = f == filter
            Surface(
                onClick = { onFilterChange(f) },
                shape = RoundedCornerShape(50),
                color = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelCard(state: ModelState, vm: ModelManagerViewModel, onConfig: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (state is ModelState.Loaded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModelStatusDot(state)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        state.info.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ModelTagRow(state.info, Modifier.padding(top = 6.dp))
                }
                Surface(
                    onClick = onConfig,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Tune, "Конфигурация",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (state is ModelState.Downloading) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text(
                    "${(state.progress * 100).toInt()}% · ${humanBytes(state.bytesPerSec)}/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state is ModelState.Loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text(
                    "Загружаю модель в память…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state is ModelState.Error) {
                Text(
                    "Ошибка: ${state.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state) {
                    is ModelState.Loading -> {
                        ActionButton("Загружается…", filled = true, modifier = Modifier.fillMaxWidth(), onClick = {})
                    }
                    is ModelState.Loaded -> {
                        ActionButton("Загружена", filled = true, modifier = Modifier.weight(1f), onClick = { vm.unload() })
                        ActionButton("Удалить", modifier = Modifier.weight(1f), onClick = { vm.delete(state.info.id) })
                    }
                    is ModelState.Downloaded -> {
                        ActionButton("Загрузить", icon = Icons.Outlined.PlayArrow, filled = true,
                            modifier = Modifier.weight(1f), onClick = { vm.load(state.info.id) })
                        ActionButton("Удалить", modifier = Modifier.weight(1f), onClick = { vm.delete(state.info.id) })
                    }
                    is ModelState.Available -> {
                        ActionButton("Скачать", icon = Icons.Outlined.Download, filled = true,
                            modifier = Modifier.fillMaxWidth(), onClick = { vm.startDownload(state.info.id) })
                    }
                    is ModelState.Error -> {
                        ActionButton("Повторить скачивание", icon = Icons.Outlined.Download, filled = true,
                            modifier = Modifier.fillMaxWidth(), onClick = { vm.startDownload(state.info.id) })
                    }
                    is ModelState.Downloading -> {
                        ActionButton("Отменить", icon = Icons.Outlined.Close,
                            modifier = Modifier.fillMaxWidth(), onClick = { vm.cancelDownload(state.info.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusDot(state: ModelState) {
    val color = when (state) {
        is ModelState.Loaded -> MaterialTheme.colorScheme.primary
        is ModelState.Loading -> MaterialTheme.colorScheme.tertiary
        is ModelState.Downloaded -> MaterialTheme.colorScheme.outline
        is ModelState.Downloading -> MaterialTheme.colorScheme.tertiary
        is ModelState.Available -> MaterialTheme.colorScheme.surface
        is ModelState.Error -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun ModelTagRow(info: ModelInfo, modifier: Modifier = Modifier) {
    val tags = modelTagSpecs(info)
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(tags, key = { "${it.kind}-${it.label}" }) { tag -> TagChip(tag) }
    }
}

@Composable
private fun TagChip(tag: ModelTagSpec) {
    val (bg, fg) = tagColors(tag.kind)
    Surface(shape = RoundedCornerShape(50), color = bg.copy(alpha = 0.72f)) {
        Text(
            tag.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun tagColors(kind: ModelTagKind): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (kind) {
        ModelTagKind.Accelerator -> colors.primaryContainer to colors.onPrimaryContainer
        ModelTagKind.Size -> colors.secondaryContainer to colors.onSecondaryContainer
        ModelTagKind.Vision -> colors.tertiaryContainer to colors.onTertiaryContainer
        ModelTagKind.Audio -> colors.surfaceVariant to colors.onSurfaceVariant
        ModelTagKind.Thinking -> colors.errorContainer to colors.onErrorContainer
        ModelTagKind.Speculative -> colors.inverseSurface to colors.inverseOnSurface
    }
}

@Composable
private fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = bg, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(18.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.Medium)
        }
    }
}

