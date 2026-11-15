package com.localchat.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localchat.domain.model.Accelerator

@Composable
fun AddModelScreen(
    onBack: () -> Unit,
    vm: ModelManagerViewModel = hiltViewModel(),
) {
    var repoId by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var contextSize by remember { mutableStateOf("8192") }
    var accelerator by remember { mutableStateOf(Accelerator.GPU) }
    var vision by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }

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
                "Add model",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            FieldLabel("HuggingFace repo ID")
            FormField(repoId, onChange = { repoId = it }, placeholder = "google/gemma-3-it-litert")
            FieldLabel("File (.litertlm / .task)", topPadding = 16)
            FormField(file, onChange = { file = it }, placeholder = "gemma-3-1b-it-int4.litertlm")
            FieldLabel("Display name", topPadding = 16)
            FormField(displayName, onChange = { displayName = it }, placeholder = "Gemma 3 1B")

            FieldLabel("Context size (tokens)", topPadding = 16)
            FormField(
                value = contextSize,
                onChange = { v -> contextSize = v.filter { it.isDigit() } },
                placeholder = "8192",
                keyboardType = KeyboardType.Number,
            )

            FieldLabel("Execution device", topPadding = 16)
            AcceleratorSelector(current = accelerator, onSelect = { accelerator = it })

            Box(Modifier.padding(top = 16.dp)) {
                CheckboxRow("Supports vision", vision) { vision = it }
            }
            CheckboxRow("Supports thinking", thinking) { thinking = it }

            val ctx = contextSize.toIntOrNull() ?: 0
            val canSubmit = repoId.isNotBlank() && file.isNotBlank() && displayName.isNotBlank() && ctx > 0
            Surface(
                onClick = {
                    if (canSubmit) {
                        vm.addCustom(
                            repoId = repoId.trim(),
                            file = file.trim(),
                            displayName = displayName.trim(),
                            vision = vision,
                            thinking = thinking,
                            contextSize = ctx,
                            accelerator = accelerator,
                        )
                        onBack()
                    }
                },
                shape = RoundedCornerShape(50),
                color = if (canSubmit) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp),
            ) {
                Text(
                    "Add",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (canSubmit) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AcceleratorSelector(current: Accelerator, onSelect: (Accelerator) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(Accelerator.GPU, Accelerator.CPU).forEach { accel ->
                val active = accel == current
                Surface(
                    onClick = { onSelect(accel) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        accel.name,
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
private fun FieldLabel(text: String, topPadding: Int = 0) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = topPadding.dp, bottom = 6.dp),
    )
}

@Composable
private fun FormField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            BasicTextField(
                value = value, onValueChange = onChange,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CheckboxRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}


