package com.localchat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ChatTopBar(
    modelName: String,
    modelStatus: String,
    meter: ModelTopMeter?,
    hasModel: Boolean,
    isLoading: Boolean,
    hasDownloadedModel: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenConfigEnabled: Boolean = hasModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RoundIconButton(icon = Icons.Outlined.Menu, contentDesc = "Чаты", onClick = onOpenDrawer)

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenModelPicker),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dotColor = when {
                    hasModel -> MaterialTheme.colorScheme.primary
                    isLoading -> MaterialTheme.colorScheme.tertiary
                    hasDownloadedModel -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                }
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(dotColor),
                )
                Column(Modifier.weight(1f).padding(start = 8.dp, end = 4.dp)) {
                    Text(
                        modelName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (meter == null) {
                        Text(
                            modelStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else {
                        InlineMeter(meter)
                    }
                }
                Icon(
                    Icons.Outlined.ExpandMore, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Кнопка настроек модели всегда видна (для симметрии). Если активной модели нет —
        // приглушаем и кликом открываем picker, чтобы пользователь сначала её выбрал.
        RoundIconButton(
            icon = Icons.Outlined.Tune,
            contentDesc = "Настройки модели",
            onClick = if (onOpenConfigEnabled) onOpenConfig else onOpenModelPicker,
            tint = if (onOpenConfigEnabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

data class ModelTopMeter(
    val label: String,
    val progress: Float?,
)

@Composable
private fun InlineMeter(meter: ModelTopMeter) {
    // Анимируем только числовое значение прогресса — без crossfade на каждый
    // ре-композ. Раньше AnimatedContent перерисовывал весь meter на каждый
    // токен стрима, отсюда мерцание.
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = meter.progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 220,
            easing = androidx.compose.animation.core.LinearEasing,
        ),
        label = "context-meter-progress",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .padding(top = 3.dp),
    ) {
        if (meter.progress == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(15.dp)
                    .clip(RoundedCornerShape(50)),
            )
        } else {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(15.dp)
                    .clip(RoundedCornerShape(50)),
            )
        }
        Text(
            meter.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
            maxLines = 1,
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDesc, tint = tint)
        }
    }
}
