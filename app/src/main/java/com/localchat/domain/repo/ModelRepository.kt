package com.localchat.domain.repo

import com.localchat.domain.model.Accelerator
import com.localchat.domain.model.EmbeddingFiles
import com.localchat.domain.model.ModelState
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    fun observeStates(): Flow<List<ModelState>>
    fun observeActive(): Flow<ModelState.Loaded?>

    /**
     * Files for the ACTIVE embedding model (selected in settings, or the only downloaded one),
     * or null when semantic RAG is off / no embedding model is downloaded. Used by the embedder;
     * the semantic-RAG toggle is folded in here so callers get a single "ready or not" signal.
     */
    fun embeddingModelFiles(): EmbeddingFiles?

    suspend fun load(modelId: String)
    suspend fun unload()
    suspend fun startDownload(modelId: String)
    suspend fun cancelDownload(modelId: String)
    suspend fun delete(modelId: String)
    suspend fun deleteAll()
    suspend fun updateConfig(
        modelId: String,
        contextSize: Int,
        maxTokens: Int,
        topK: Int,
        topP: Float,
        temperature: Float,
        accelerator: Accelerator,
        enableSpeculative: Boolean,
    )
    suspend fun addCustom(
        repoId: String,
        file: String,
        displayName: String,
        supportsVision: Boolean,
        supportsThinking: Boolean,
        contextSize: Int,
        accelerator: Accelerator,
    )
}
