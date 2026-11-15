package com.localchat.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localchat.domain.model.Accelerator
import com.localchat.domain.model.ModelInfo

@Composable
fun ConfigDialog(
    info: ModelInfo,
    onDismiss: () -> Unit,
    onConfirm: (contextSize: Int, maxTokens: Int, topK: Int, topP: Float, temperature: Float, accelerator: Accelerator, enableSpeculative: Boolean) -> Unit,
) {
    var contextSize by remember { mutableStateOf(info.contextSize.toString()) }
    var maxTokens by remember { mutableStateOf(info.maxTokens.toString()) }
    var topK by remember { mutableStateOf(info.topK.toFloat()) }
    var topP by remember { mutableStateOf(info.topP) }
    var temperature by remember { mutableStateOf(info.temperature) }
    var accelerator by remember { mutableStateOf(info.accelerator) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Конфигурация",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        text = {
            Column {
                NumberField(
                    label = "Контекст чата (токены)",
                    value = contextSize,
                    onChange = { v -> contextSize = v.filter { it.isDigit() }.take(6) },
                )
                Text(
                    "Сколько истории и RAG-контекста отдавать модели перед ответом.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                NumberField(
                    label = "Токенов на ответ",
                    value = maxTokens,
                    onChange = { v -> maxTokens = v.filter { it.isDigit() }.take(6) },
                )
                Text(
                    "Максимум токенов, которые модель может сгенерировать за один ответ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
                SliderField(
                    label = "TopK",
                    value = topK,
                    onChange = { topK = it },
                    valueRange = 1f..128f,
                    display = topK.toInt().toString(),
                )
                Spacer(Modifier.height(16.dp))
                SliderField(
                    label = "TopP",
                    value = topP,
                    onChange = { topP = it },
                    valueRange = 0f..1f,
                    display = "%.2f".format(topP),
                )
                Spacer(Modifier.height(16.dp))
                SliderField(
                    label = "Temperature",
                    value = temperature,
                    onChange = { temperature = it },
                    valueRange = 0f..2f,
                    display = "%.2f".format(temperature),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "Accelerator",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AcceleratorRow(current = accelerator, onSelect = { accelerator = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ctx = (contextSize.toIntOrNull() ?: info.contextSize).coerceIn(256, 128_000)
                val out = (maxTokens.toIntOrNull() ?: info.maxTokens).coerceIn(1, ctx)
                onConfirm(
                    ctx,
                    out,
                    topK.toInt().coerceAtLeast(1),
                    topP.coerceIn(0f, 1f),
                    temperature.coerceAtLeast(0f),
                    accelerator,
                    false,
                )
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
    }
}

@Composable
private fun SliderField(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Text(
                    display,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun AcceleratorRow(current: Accelerator, onSelect: (Accelerator) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(Accelerator.GPU, Accelerator.CPU).forEach { acc ->
                val active = acc == current
                Surface(
                    onClick = { onSelect(acc) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        acc.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
