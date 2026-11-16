package com.localchat.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.localchat.data.prefs.AppLanguage
import com.localchat.data.prefs.displayName
import com.localchat.data.prefs.ThemeMode
import com.localchat.domain.model.ModelInfo

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val settings by vm.settings.collectAsState()
    val embeddingModels by vm.embeddingModels.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current
    var confirmClearChats by remember { mutableStateOf(false) }
    var confirmDeleteModels by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
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
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Appearance")
            LanguageSelector(current = settings.language, onSelect = vm::setLanguage)
            ThemeSelector(current = settings.theme, onSelect = vm::setTheme)
            ToggleRow(
                "Dynamic colors (Material You)",
                settings.dynamicColor,
            ) { vm.setDynamicColor(it) }

            Section("Data", topPad = 16)
            NavRow("Clear chat history") { confirmClearChats = true }
            NavRow("Delete all models") { confirmDeleteModels = true }

            Section("HuggingFace", topPad = 16)
            HfTokenRow(
                token = settings.hfToken,
                onChange = vm::setHfToken,
            )

            Section("Semantic search (RAG)", topPad = 16)
            ToggleRow(
                "Semantic document search",
                settings.semanticRagEnabled,
            ) { vm.setSemanticRag(it) }
            EmbeddingModelSelector(
                models = embeddingModels,
                selectedId = settings.embeddingModelId,
                enabled = settings.semanticRagEnabled,
                onSelect = vm::setEmbeddingModel,
            )

            Section("Assistant", topPad = 16)
            SystemPromptRow(
                prompt = settings.systemPrompt,
                onChange = vm::setSystemPrompt,
                onReset = vm::resetSystemPrompt,
            )

            Section("About", topPad = 16)
            InfoRow("Licenses", "LiteRT, Markwon, AndroidX")
            InfoRow("Version", "0.04")
        }
    }

    if (confirmClearChats) {
        AlertDialog(
            onDismissRequest = { confirmClearChats = false },
            title = { Text("Clear history?") },
            text = { Text("All saved chats and messages will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearChats = false
                    vm.clearChats()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClearChats = false }) { Text("Cancel") } },
        )
    }

    if (confirmDeleteModels) {
        AlertDialog(
            onDismissRequest = { confirmDeleteModels = false },
            title = { Text("Delete models?") },
            text = { Text("Downloaded model files will be deleted. Available models will remain listed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteModels = false
                    vm.deleteAllModels()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteModels = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LanguageSelector(current: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Interface language",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        current.displayName(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayName()) },
                    onClick = {
                        expanded = false
                        onSelect(language)
                    },
                )
            }
        }
    }
}@Composable
private fun ThemeSelector(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val items = listOf(
                ThemeMode.System to "System",
                ThemeMode.Light to "Light",
                ThemeMode.Dark to "Dark",
            )
            items.forEach { (mode, label) ->
                val active = mode == current
                Surface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbeddingModelSelector(
    models: List<ModelInfo>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    if (models.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "No embedding model is installed. Open Models → Embedding and download one " +
                    "(for example, EmbeddingGemma). Lexical search is used in the meantime.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        return
    }
    val current = models.firstOrNull { it.id == selectedId } ?: models.first()
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = { if (enabled) expanded = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Embedding model",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        current.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.displayName) },
                    onClick = { expanded = false; onSelect(m.id) },
                )
            }
        }
    }
}

@Composable
private fun Section(text: String, topPad: Int = 0) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = topPad.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HfTokenRow(token: String, onChange: (String) -> Unit) {
    var local by remember(token) { mutableStateOf(token) }
    var revealed by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "Hugging Face token",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = local,
                onValueChange = {
                    local = it
                    onChange(it)
                },
                singleLine = true,
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                decorationBox = { inner ->
                    Box {
                        if (local.isEmpty()) {
                            Text(
                                "hf_xxx…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Needed to download Gemma. Create one at huggingface.co/settings/tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = { revealed = !revealed },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        if (revealed) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemPromptRow(
    prompt: String,
    onChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    var local by remember(prompt) { mutableStateOf(prompt) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "System prompt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = {
                        onReset()
                    },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        "Reset",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            BasicTextField(
                value = local,
                onValueChange = {
                    local = it
                    onChange(it)
                },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .padding(top = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (local.isEmpty()) {
                            Text(
                                "Enter a system prompt",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}




