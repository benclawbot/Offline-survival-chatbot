package com.localchat.ui.chat

import com.localchat.domain.model.ModelInfo

data class Capability(
    val available: Boolean,
    val reason: String? = null,
)

data class ChatCapabilities(
    val imageInput: Capability,
    val documentInput: Capability,
    val voiceInput: Capability,
    val thinking: Capability,
    val webSearch: Capability,
)

/**
 * @param visionOverride when non-null, the PROBED vision capability of the loaded bundle —
 *   preferred over [ModelInfo.supportsVision], which is only a family-based guess.
 */
fun chatCapabilitiesFor(model: ModelInfo?, visionOverride: Boolean? = null): ChatCapabilities {
    val noModel = "Сначала загрузи модель"
    val vision = visionOverride ?: model?.supportsVision == true
    return ChatCapabilities(
        imageInput = when {
            model == null -> Capability(false, noModel)
            vision -> Capability(true)
            else -> Capability(false, "Нужна vision-модель")
        },
        documentInput = if (model != null) Capability(true) else Capability(false, noModel),
        voiceInput = Capability(true),
        thinking = when {
            model == null -> Capability(false, noModel)
            model.supportsThinking -> Capability(true)
            else -> Capability(false, "Модель не поддерживает thinking")
        },
        webSearch = Capability(false, "Offline build: local library search is always enabled"),
    )
}
