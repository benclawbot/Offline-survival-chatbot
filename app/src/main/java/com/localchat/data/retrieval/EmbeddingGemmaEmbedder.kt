package com.localchat.data.retrieval

import android.util.Log
import com.localchat.domain.repo.ModelRepository
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device multilingual embeddings via EmbeddingGemma (100+ languages incl. Russian), loaded
 * through the AI Edge RAG SDK's on-device embedder. Used for embeddings only — generation stays on
 * our LiteRT-LM runtime.
 *
 * Output is 768-dim; we keep the first [dims]=128 (EmbeddingGemma is Matryoshka-trained, so the
 * leading dims are a valid lower-dim embedding) and L2-normalize, so cosine = dot and Room rows
 * stay small. Query/document task prefixes are applied by the SDK from the [EmbedData.TaskType].
 *
 * The active model + its files come from [ModelRepository.embeddingModelFiles] (selected in
 * Settings, downloaded in the Models screen, gated by the semantic-RAG toggle). [isReady] is false
 * when RAG is off or no embedding model is downloaded → the retriever falls back to lexical-only.
 * Any load/inference failure also degrades to empty. The model is reloaded if the active file path
 * changes (user switched embedding models).
 */
@Singleton
class EmbeddingGemmaEmbedder @Inject constructor(
    private val modelRepo: ModelRepository,
) : TextEmbedder {

    override val dims: Int = 128

    override val isReady: Boolean
        get() = modelRepo.embeddingModelFiles() != null

    private val lock = Any()
    @Volatile private var model: GeckoEmbeddingModel? = null
    @Volatile private var loadedModelPath: String? = null

    /** Load (or reuse) the embedder for the currently active embedding model. */
    private fun model(): GeckoEmbeddingModel? {
        val files = modelRepo.embeddingModelFiles() ?: return null
        val path = files.model.absolutePath
        model?.let { if (path == loadedModelPath) return it }
        return synchronized(lock) {
            if (model != null && path == loadedModelPath) return model
            runCatching {
                // Embeddings on CPU to avoid contending with the LLM on the GPU.
                GeckoEmbeddingModel(path, Optional.of(files.tokenizer.absolutePath), false)
            }.onFailure { Log.w(TAG, "EmbeddingGemma load failed → lexical-only: ${it.message}") }
                .getOrNull()?.also { model = it; loadedModelPath = path }
        }
    }

    override suspend fun embed(texts: List<String>, kind: EmbedKind): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val m = model() ?: return emptyList()
        val task = when (kind) {
            EmbedKind.QUERY -> EmbedData.TaskType.RETRIEVAL_QUERY
            EmbedKind.DOCUMENT -> EmbedData.TaskType.RETRIEVAL_DOCUMENT
        }
        return withContext(Dispatchers.Default) {
            runCatching {
                val request = EmbeddingRequest.create(texts.map { EmbedData.create<String>(it, task) })
                // Off the main thread → blocking get() on the ListenableFuture is fine.
                m.getBatchEmbeddings(request).get().map { row -> toVector(row) }
            }.onFailure { Log.w(TAG, "embed failed → lexical-only: ${it.message}") }
                .getOrElse { emptyList() }
        }
    }

    /** Truncate to [dims] (Matryoshka) and L2-normalize. */
    private fun toVector(row: List<Float>): FloatArray {
        val n = minOf(dims, row.size)
        val v = FloatArray(n) { row[it] }
        return VectorMath.l2normalize(v)
    }

    companion object {
        private const val TAG = "EmbeddingGemma"
    }
}
