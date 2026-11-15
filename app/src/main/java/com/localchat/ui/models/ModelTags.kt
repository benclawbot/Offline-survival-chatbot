package com.localchat.ui.models

import com.localchat.domain.model.ModelInfo

internal enum class ModelTagKind { Accelerator, Size, Vision, Audio, Thinking, Speculative }

internal data class ModelTagSpec(
    val label: String,
    val kind: ModelTagKind,
)

internal fun modelTagSpecs(info: ModelInfo): List<ModelTagSpec> = buildList {
    add(ModelTagSpec(humanBytes(info.sizeBytes), ModelTagKind.Size))
    if (info.supportsVision) add(ModelTagSpec("vision", ModelTagKind.Vision))
    if (info.supportsAudio) add(ModelTagSpec("audio", ModelTagKind.Audio))
    if (info.supportsThinking) add(ModelTagSpec("thinking", ModelTagKind.Thinking))
    if (info.supportsSpeculative) add(ModelTagSpec("spec", ModelTagKind.Speculative))
}
