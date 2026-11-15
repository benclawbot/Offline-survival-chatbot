package com.localchat.data.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val pinned: Boolean = false,
    val incognito: Boolean = false,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chat_id"), Index("chat_id", "created_at")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chat_id") val chatId: String,
    val role: String,
    val text: String,
    /** JSON serialized List<Attachment> */
    @ColumnInfo(name = "attachments_json") val attachmentsJson: String,
    val thinking: String?,
    @ColumnInfo(name = "thinking_ms") val thinkingMs: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long?,
    @ColumnInfo(name = "tokens_per_sec") val tokensPerSec: Float?,
    @ColumnInfo(name = "token_count") val tokenCount: Int?,
    @ColumnInfo(name = "is_streaming") val isStreaming: Boolean,
    /** JSON-serialized [ToolPart] (Call or Result), or null for plain messages. */
    @ColumnInfo(name = "tool_json") val toolJson: String? = null,
)

/**
 * Cache of extracted text per document attachment. Populated on attach so we
 * never depend on `content://` URIs surviving process restarts (SAF revokes
 * them by default; even with persistable permission a reboot can lose them).
 */
@Entity(tableName = "document_cache")
data class DocumentCacheEntity(
    /** [Attachment.Document.id] — stable across process restarts. */
    @PrimaryKey val id: String,
    val name: String,
    /** Extracted plain text. May be empty if extraction failed (we cache the failure too). */
    val text: String,
    @ColumnInfo(name = "extracted_at") val extractedAt: Long,
)

/**
 * Cache of fetched web pages per chat. The agent's retriever rehydrates from
 * this on chat open so previously visited pages remain searchable across
 * process restarts without re-fetching from the network.
 */
@Entity(
    tableName = "web_page_cache",
    primaryKeys = ["chat_id", "url"],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chat_id")],
)
data class WebPageCacheEntity(
    @ColumnInfo(name = "chat_id") val chatId: String,
    val url: String,
    val title: String,
    val text: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/**
 * Persistent per-chat embedding store. Each row is one chunk's dense vector (L2-normalized
 * float32, serialized little-endian to a BLOB). On chat open the retriever rehydrates these
 * so semantic search survives restarts WITHOUT recomputing embeddings (which are slow). Scoped
 * per chat and cascade-deleted with the chat. [chunkId] = "$docId#$ordinal" (deterministic).
 */
@Entity(
    tableName = "embeddings",
    primaryKeys = ["chat_id", "chunk_id"],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chat_id")],
)
data class EmbeddingEntity(
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(name = "chunk_id") val chunkId: String,
    @ColumnInfo(name = "chunk_text") val chunkText: String,
    /** Base64 of the little-endian float32 vector (see VectorMath + RoomChatRepository). */
    val vector: String,
    val dims: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
