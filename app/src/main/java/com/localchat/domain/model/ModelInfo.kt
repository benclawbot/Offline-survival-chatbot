package com.localchat.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class ModelInfo(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsThinking: Boolean = false,
    /** Whether the model reliably follows the tool-call protocol (agent web/file tools).
     *  Most instruct chat models do; set false for base/embedding models. */
    val supportsTools: Boolean = true,
    val supportsSpeculative: Boolean = false,
    val accelerator: Accelerator = Accelerator.GPU,
    val estimatedRamBytes: Long = sizeBytes * 2,
    val expectedSha256: String? = null,
    val source: ModelSource = ModelSource.Builtin,
    val maxTokens: Int = 2048,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val temperature: Float = 0.8f,
    val contextSize: Int = 8192,
    val enableSpeculative: Boolean = false,
    /** LLM (chat/generation) vs Embedding (semantic-RAG) model. */
    val kind: ModelKind = ModelKind.Llm,
)

enum class Accelerator { CPU, GPU }

enum class ModelKind { Llm, Embedding }

sealed interface ModelSource {
    data object Builtin : ModelSource
    data class HuggingFace(
        val repoId: String,
        val file: String,
        val revision: String = "main",
        /** Optional second file (e.g. a SentencePiece tokenizer for embedding models). */
        val tokenizerFile: String? = null,
    ) : ModelSource
    data class LocalFile(val absolutePath: String) : ModelSource
}

/** Resolved on-disk files for the active embedding model (model weights + tokenizer). */
data class EmbeddingFiles(val model: java.io.File, val tokenizer: java.io.File)

sealed interface ModelState {
    val info: ModelInfo

    data class Available(override val info: ModelInfo) : ModelState
    data class Loading(override val info: ModelInfo) : ModelState
    data class Downloading(
        override val info: ModelInfo,
        val bytesDownloaded: Long,
        val bytesPerSec: Long,
    ) : ModelState {
        val progress: Float
            get() = if (info.sizeBytes > 0L) {
                (bytesDownloaded.toFloat() / info.sizeBytes).coerceIn(0f, 1f)
            } else {
                0f
            }
    }
    data class Downloaded(override val info: ModelInfo) : ModelState
    data class Loaded(override val info: ModelInfo) : ModelState
    data class Error(override val info: ModelInfo, val message: String) : ModelState
}

