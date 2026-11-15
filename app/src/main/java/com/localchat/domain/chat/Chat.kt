package com.localchat.domain.chat

import androidx.compose.runtime.Immutable

@Immutable
data class Chat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val incognito: Boolean = false,
    val modelId: String? = null,
)

@Immutable
data class Message(
    val id: String,
    val chatId: String,
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val thinking: String? = null,
    /** Wall-clock the model spent in the thinking channel for this message, if any. */
    val thinkingMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val latencyMs: Long? = null,
    val tokensPerSec: Float? = null,
    val tokenCount: Int? = null,
    val isStreaming: Boolean = false,
    /** Non-null = tool call/result rendered as a card instead of a normal bubble. */
    val tool: ToolPart? = null,
)

enum class Role { User, Assistant }

sealed interface Attachment {
    val id: String
    data class Image(override val id: String, val uri: String, val name: String) : Attachment
    data class Document(override val id: String, val uri: String, val name: String, val sizeBytes: Long) : Attachment
}

/**
 * Inline action card rendered inside chat. Each tool call and each tool result
 * gets its own [Message] with [tool] set, so the timeline reads as:
 *     user -> assistant text -> ToolCall(search) -> ToolResult -> assistant text -> ...
 */
sealed interface ToolPart {
    val name: String

    data class Call(
        override val name: String,
        val argumentsJson: String,
    ) : ToolPart

    data class Result(
        override val name: String,
        val ok: Boolean,
        val summary: String,
        val payload: String,
    ) : ToolPart
}

