package com.localchat.runtime

import com.localchat.domain.model.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LiteRtRuntime {
    val state: Flow<RuntimeState>

    /**
     * Capabilities detected from the ACTUAL loaded bundle (not from the model definition's
     * heuristic flags). `null` until a model is loaded and probed; reset to `null` on unload.
     * The app prefers these over [ModelInfo]'s declared flags — a bundle can advertise a
     * family (e.g. Gemma = vision) yet ship without the encoder.
     */
    val capabilities: StateFlow<RuntimeCapabilities?>

    suspend fun load(
        model: ModelInfo,
        modelPath: String,
        supportImage: Boolean = false,
        systemPrompt: String = "",
    )
    suspend fun unload()
    suspend fun resetConversation(
        systemPrompt: String? = null,
        initialMessages: List<RuntimeChatMessage> = emptyList(),
        model: ModelInfo? = null,
    )

    fun stream(
        prompt: String,
        images: List<ByteArray> = emptyList(),
        extraContext: Map<String, String> = emptyMap(),
    ): Flow<InferenceChunk>

    fun stop()
}

sealed interface RuntimeState {
    data object Idle : RuntimeState
    data class Loading(val modelId: String) : RuntimeState
    data class Loaded(val modelId: String) : RuntimeState
    data class Error(val message: String) : RuntimeState
}

/**
 * What the loaded bundle is set up to do, determined at load time.
 *  - [templateFamily] is read from the model's OWN chat template via the engine's render call
 *    (not the file name) — it drives the tool dialect and reasoning-channel markers.
 *  - [visionEnabled] reflects whether a vision backend was configured for this load (curated
 *    flag + GPU vision backend). We don't encode-probe: "Failed to decode image" is an image
 *    decode limitation, not a capability signal, so it would false-negative.
 *  - [supportsSpeculative] is from the litertlm Capabilities API.
 */
data class RuntimeCapabilities(
    val modelId: String,
    val visionEnabled: Boolean,
    val templateFamily: TemplateFamily,
    val supportsSpeculative: Boolean,
)

sealed interface InferenceChunk {
    data class Token(val text: String, val thinking: String? = null) : InferenceChunk
    data class Done(val metrics: InferenceMetrics) : InferenceChunk
    data class Error(val message: String) : InferenceChunk
}

data class InferenceMetrics(
    val firstTokenLatencyMs: Long,
    val totalTimeMs: Long,
    val tokenCount: Int,
) {
    val tokensPerSec: Float
        get() = if (totalTimeMs > 0) tokenCount * 1000f / totalTimeMs else 0f
}

