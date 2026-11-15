package com.localchat.domain.repo

import com.localchat.domain.chat.Chat
import com.localchat.domain.chat.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeChats(): Flow<List<Chat>>
    fun observeMessages(chatId: String): Flow<List<Message>>
    suspend fun getMessages(chatId: String): List<Message>
    suspend fun createChat(title: String, incognito: Boolean = false): Chat
    suspend fun renameChat(chatId: String, title: String)
    suspend fun deleteChat(chatId: String)
    suspend fun deleteAllChats()
    suspend fun togglePin(chatId: String)
    suspend fun appendMessage(message: Message)
    suspend fun appendMessages(messages: List<Message>)
    suspend fun updateMessage(message: Message)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteMessagesAfter(chatId: String, fromMessageId: String)

    /** Cached extracted text for an attached document (key = Attachment.Document.id). */
    suspend fun getCachedDocumentText(attachmentId: String): String?
    suspend fun cacheDocumentText(attachmentId: String, name: String, text: String)

    /** Cached web pages, scoped to a chat — survives process restarts so the
     *  retriever can be rehydrated without re-fetching the network. */
    suspend fun getCachedWebPages(chatId: String): List<CachedWebPage>
    suspend fun cacheWebPage(chatId: String, url: String, title: String, text: String)

    /** Per-chat chunk embeddings — persisted so semantic search survives restarts without
     *  recomputing (slow). Vectors are L2-normalized float32. */
    suspend fun getEmbeddings(chatId: String): List<ChunkEmbedding>
    suspend fun saveEmbeddings(chatId: String, docId: String, items: List<ChunkEmbedding>)
    suspend fun deleteEmbeddings(chatId: String, docId: String)
}

data class CachedWebPage(
    val url: String,
    val title: String,
    val text: String,
    val fetchedAt: Long,
)

/** One chunk's embedding as the domain sees it (vector as a plain FloatArray). */
data class ChunkEmbedding(
    val docId: String,
    val chunkId: String,
    val chunkText: String,
    val vector: FloatArray,
    val dims: Int,
)
