package com.localchat.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localchat.domain.model.ModelInfo
import com.localchat.domain.model.ModelState
import com.localchat.ui.common.DeviceMemorySnapshot
import com.localchat.ui.common.rememberDeviceMemorySnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    states: List<ModelState>,
    onLoad: (String) -> Unit,
    onUnload: () -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val loaded = states.firstOrNull { it is ModelState.Loaded } as? ModelState.Loaded
    val loading = states.filterIsInstance<ModelState.Loading>()
    val downloaded = states.filterIsInstance<ModelState.Downloaded>()
    val downloading = states.filterIsInstance<ModelState.Downloading>()
    val errors = states.filterIsInstance<ModelState.Error>()
    val available = states.filterIsInstance<ModelState.Available>()
    val memory by rememberDeviceMemorySnapshot()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(max = 620.dp),
        ) {
            item("resources") {
                ResourceHeader(
                    memory = memory,
                )
            }
            if (loaded != null) {
                item("loaded-label") { SectionLabel("Loaded in memory") }
                item("loaded-${loaded.info.id}") { LoadedRow(loaded.info, onUnload) }
            }
            if (loading.isNotEmpty()) {
                item("loading-label") { SectionLabel("Loading", topPadding = 16) }
                items(loading, key = { "loading-${it.info.id}" }) { st ->
                    LoadingRow(st.info)
                }
            }
            if (downloaded.isNotEmpty()) {
                item("downloaded-label") { SectionLabel("Downloaded (ready to load)", topPadding = 16) }
                items(downloaded, key = { "downloaded-${it.info.id}" }) { ds ->
                    DownloadedRow(ds.info, onLoad = { onLoad(ds.info.id) })
                }
            }
            if (downloading.isNotEmpty()) {
                item("downloading-label") { SectionLabel("Downloading", topPadding = 16) }
                items(downloading, key = { "downloading-${it.info.id}" }) { st ->
                    DownloadingRow(st, onCancel = { onCancelDownload(st.info.id) })
                }
            }
            if (errors.isNotEmpty()) {
                item("errors-label") { SectionLabel("Needs attention", topPadding = 16) }
                items(errors, key = { "error-${it.info.id}" }) { st ->
                    ErrorRow(st, onDownload = { onDownload(st.info.id) })
                }
            }
            if (available.isNotEmpty()) {
                item("available-label") { SectionLabel("Available to download", topPadding = 16) }
                items(available, key = { "available-${it.info.id}" }) { st ->
                    AvailableRow(st.info, onDownload = { onDownload(st.info.id) })
                }
            }
            item("manage") {
                Surface(
                    onClick = onManage,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Manage models",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceHeader(memory: DeviceMemorySnapshot) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RAM",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    "${memory.usedPercent}% · free ${humanMb(memory.availMb)} · LocalChat ${humanMb(memory.appMb)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            LinearProgressIndicator(
                progress = { memory.usedPercent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, topPadding: Int = 4) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = topPadding.dp, bottom = 8.dp),
    )
}

@Composable
private fun ErrorRow(state: ModelState.Error, onDownload: () -> Unit) {
    Surface(
        onClick = onDownload,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    state.info.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    state.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                    maxLines = 2,
                )
            }
            Icon(Icons.Outlined.Download, "Retry download", tint = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun LoadingRow(info: ModelInfo) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        info.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                    PickerTagRow(info, Modifier.padding(top = 4.dp))
                }
            }
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

@Composable
private fun LoadedRow(info: ModelInfo, onUnload: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    info.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                )
                PickerTagRow(info, Modifier.padding(top = 4.dp))
            }
            Surface(
                onClick = onUnload,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Stop, "Unload", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun DownloadingRow(state: ModelState.Downloading, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        state.info.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        "${(state.progress * 100).toInt()}% · ${humanBytes(state.bytesPerSec)}/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    )
                }
                Surface(
                    onClick = onCancel,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun DownloadedRow(info: ModelInfo, onLoad: () -> Unit) {
    Surface(
        onClick = onLoad,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    info.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PickerTagRow(info, Modifier.padding(top = 4.dp))
            }
            Icon(Icons.Outlined.PlayArrow, "Load", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun AvailableRow(info: ModelInfo, onDownload: () -> Unit) {
    Surface(
        onClick = onDownload,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    info.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PickerTagRow(info, Modifier.padding(top = 4.dp))
            }
            Icon(Icons.Outlined.Download, "Download", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

internal fun humanBytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb < 1000) "%.0f MB".format(mb) else "%.1f GB".format(mb / 1000)
}

private fun humanMb(mb: Long): String = if (mb < 1024) "$mb MB" else "%.1f GB".format(mb / 1024.0)

@Composable
private fun PickerTagRow(info: ModelInfo, modifier: Modifier = Modifier) {
    val tags = modelTagSpecs(info)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        tags.take(4).forEach { tag ->
            val (bg, fg) = pickerTagColors(tag.kind)
            Surface(shape = RoundedCornerShape(50), color = bg.copy(alpha = 0.72f)) {
                Text(
                    tag.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun pickerTagColors(kind: ModelTagKind): Pair<Color, Color> {
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
