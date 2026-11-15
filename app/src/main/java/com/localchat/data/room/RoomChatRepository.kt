package com.localchat.data.room

import com.localchat.domain.chat.Attachment
import com.localchat.domain.chat.Chat
import com.localchat.domain.chat.Message
import com.localchat.domain.chat.Role
import com.localchat.domain.chat.ToolPart
import com.localchat.data.retrieval.VectorMath
import com.localchat.domain.repo.CachedWebPage
import com.localchat.domain.repo.ChatRepository
import com.localchat.domain.repo.ChunkEmbedding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private sealed interface AttachmentDto {
    val id: String
    val uri: String
    val name: String

    @Serializable
    data class Image(override val id: String, override val uri: String, override val name: String) : AttachmentDto

    @Serializable
    data class Document(
        override val id: String, override val uri: String, override val name: String,
        val sizeBytes: Long,
    ) : AttachmentDto
}

@Serializable
private sealed interface ToolDto {
    val name: String

    @Serializable
    data class Call(override val name: String, val argumentsJson: String) : ToolDto

    @Serializable
    data class Result(
        override val name: String,
        val ok: Boolean,
        val summary: String,
        val payload: String,
    ) : ToolDto
}

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

private fun List<Attachment>.toJson(): String = json.encodeToString(
    ListSerializer(AttachmentDto.serializer()),
    map {
        when (it) {
            is Attachment.Image -> AttachmentDto.Image(it.id, it.uri, it.name)
            is Attachment.Document -> AttachmentDto.Document(it.id, it.uri, it.name, it.sizeBytes)
        }
    },
)

private fun String.fromJsonAttachments(): List<Attachment> = runCatching {
    json.decodeFromString(ListSerializer(AttachmentDto.serializer()), this).map {
        when (it) {
            is AttachmentDto.Image -> Attachment.Image(it.id, it.uri, it.name)
            is AttachmentDto.Document -> Attachment.Document(it.id, it.uri, it.name, it.sizeBytes)
        }
    }
}.getOrDefault(emptyList())

private fun ToolPart.toJson(): String = json.encodeToString(
    ToolDto.serializer(),
    when (this) {
        is ToolPart.Call -> ToolDto.Call(name, argumentsJson)
        is ToolPart.Result -> ToolDto.Result(name, ok, summary, payload)
    },
)

private fun String.fromJsonTool(): ToolPart? = runCatching {
    when (val dto = json.decodeFromString(ToolDto.serializer(), this)) {
        is ToolDto.Call -> ToolPart.Call(dto.name, dto.argumentsJson)
        is ToolDto.Result -> ToolPart.Result(dto.name, dto.ok, dto.summary, dto.payload)
    }
}.getOrNull()

private fun ChatEntity.toDomain() = Chat(id, title, updatedAt, pinned, incognito, modelId)
private fun Chat.toEntity() = ChatEntity(id, title, updatedAt, pinned, incognito, modelId)

private fun MessageEntity.toDomain() = Message(
    id = id,
    chatId = chatId,
    role = if (role == "user") Role.User else Role.Assistant,
    text = text,
    attachments = attachmentsJson.fromJsonAttachments(),
    thinking = thinking,
    thinkingMs = thinkingMs,
    createdAt = createdAt,
    latencyMs = latencyMs,
    tokensPerSec = tokensPerSec,
    tokenCount = tokenCount,
    isStreaming = isStreaming,
    tool = toolJson?.fromJsonTool(),
)

private fun Message.toEntity() = MessageEntity(
    id = id,
    chatId = chatId,
    role = if (role == Role.User) "user" else "assistant",
    text = text,
    attachmentsJson = attachments.toJson(),
    thinking = thinking,
    thinkingMs = thinkingMs,
    createdAt = createdAt,
    latencyMs = latencyMs,
    tokensPerSec = tokensPerSec,
    tokenCount = tokenCount,
    isStreaming = isStreaming,
    toolJson = tool?.toJson(),
)

@Singleton
class RoomChatRepository @Inject constructor(
    private val chats: ChatDao,
    private val messages: MessageDao,
    private val docCache: DocumentCacheDao,
    private val webCache: WebPageCacheDao,
    private val embeddings: EmbeddingDao,
) : ChatRepository {

    private val incognitoChats = MutableStateFlow<List<Chat>>(emptyList())
    private val incognitoMessages = MutableStateFlow<List<Message>>(emptyList())

    override fun observeChats(): Flow<List<Chat>> =
        combine(chats.observeAll(), incognitoChats) { db, memory ->
            (db.map { it.toDomain() } + memory)
                .sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.updatedAt })
        }

    override fun observeMessages(chatId: String): Flow<List<Message>> =
        combine(messages.observeForChat(chatId), incognitoMessages) { db, memory ->
            (db.map { it.toDomain() } + memory.filter { it.chatId == chatId })
                .sortedBy { it.createdAt }
        }

    override suspend fun getMessages(chatId: String): List<Message> =
        (messages.listForChat(chatId).map { it.toDomain() } +
            incognitoMessages.value.filter { it.chatId == chatId })
            .sortedBy { it.createdAt }

    override suspend fun createChat(title: String, incognito: Boolean): Chat {
        val chat = Chat(
            id = UUID.randomUUID().toString(),
            title = title,
            updatedAt = System.currentTimeMillis(),
            incognito = incognito,
        )
        // Incognito-чаты в БД не пишем — живут только в памяти, теряются при перезапуске.
        if (incognito) incognitoChats.value = listOf(chat) + incognitoChats.value
        else chats.insert(chat.toEntity())
        return chat
    }

    override suspend fun renameChat(chatId: String, title: String) {
        if (isIncognitoChat(chatId)) {
            incognitoChats.value = incognitoChats.value.map { if (it.id == chatId) it.copy(title = title) else it }
            return
        }
        chats.rename(chatId, title)
    }

    override suspend fun deleteChat(chatId: String) {
        if (isIncognitoChat(chatId)) {
            incognitoChats.value = incognitoChats.value.filterNot { it.id == chatId }
            incognitoMessages.value = incognitoMessages.value.filterNot { it.chatId == chatId }
            return
        }
        chats.delete(chatId)
    }

    override suspend fun deleteAllChats() {
        incognitoChats.value = emptyList()
        incognitoMessages.value = emptyList()
        messages.clearAll()
        chats.clearAll()
    }

    override suspend fun togglePin(chatId: String) {
        if (isIncognitoChat(chatId)) {
            incognitoChats.value = incognitoChats.value.map { if (it.id == chatId) it.copy(pinned = !it.pinned) else it }
            return
        }
        chats.togglePin(chatId)
    }

    override suspend fun appendMessage(message: Message) {
        if (isIncognitoChat(message.chatId)) {
            incognitoMessages.value = incognitoMessages.value.filterNot { it.id == message.id } + message
            incognitoChats.value = incognitoChats.value.map {
                if (it.id == message.chatId) it.copy(updatedAt = message.createdAt) else it
            }
            return
        }
        // Если чата ещё нет в БД (например, восстановили сообщение после гонки) — создаём.
        if (chats.getById(message.chatId) == null) {
            chats.insert(
                Chat(
                    id = message.chatId,
                    title = "Новый чат",
                    updatedAt = message.createdAt,
                ).toEntity()
            )
        }
        messages.upsert(message.toEntity())
        chats.touch(message.chatId, message.createdAt)
    }

    override suspend fun appendMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        val chatId = messages.first().chatId
        if (isIncognitoChat(chatId)) {
            val byId = messages.associateBy { it.id }
            val current = incognitoMessages.value.filterNot { it.id in byId.keys }
            incognitoMessages.value = current + messages
            val touch = messages.maxOf { it.createdAt }
            incognitoChats.value = incognitoChats.value.map {
                if (it.id == chatId) it.copy(updatedAt = touch) else it
            }
            return
        }
        if (chats.getById(chatId) == null) {
            chats.insert(
                Chat(
                    id = chatId,
                    title = "Новый чат",
                    updatedAt = messages.minOf { it.createdAt },
                ).toEntity()
            )
        }
        this.messages.upsertAll(messages.map { it.toEntity() })
        chats.touch(chatId, messages.maxOf { it.createdAt })
    }

    override suspend fun updateMessage(message: Message) {
        if (isIncognitoChat(message.chatId)) {
            incognitoMessages.value = incognitoMessages.value.map { if (it.id == message.id) message else it }
            return
        }
        messages.upsert(message.toEntity())
    }

    override suspend fun deleteMessage(messageId: String) {
        if (incognitoMessages.value.any { it.id == messageId }) {
            incognitoMessages.value = incognitoMessages.value.filterNot { it.id == messageId }
            return
        }
        messages.deleteById(messageId)
    }

    override suspend fun deleteMessagesAfter(chatId: String, fromMessageId: String) {
        if (isIncognitoChat(chatId)) {
            val target = incognitoMessages.value.firstOrNull { it.chatId == chatId && it.id == fromMessageId }
                ?: return
            incognitoMessages.value = incognitoMessages.value.filterNot {
                it.chatId == chatId && it.createdAt >= target.createdAt
            }
            return
        }
        messages.deleteFromIdInclusive(chatId, fromMessageId)
    }

    private fun isIncognitoChat(chatId: String): Boolean =
        incognitoChats.value.any { it.id == chatId }

    override suspend fun getCachedDocumentText(attachmentId: String): String? =
        docCache.get(attachmentId)?.text?.takeIf { it.isNotBlank() }

    override suspend fun cacheDocumentText(attachmentId: String, name: String, text: String) {
        docCache.upsert(
            DocumentCacheEntity(
                id = attachmentId,
                name = name,
                text = text,
                extractedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun getCachedWebPages(chatId: String): List<CachedWebPage> {
        if (isIncognitoChat(chatId)) return emptyList()
        return webCache.listForChat(chatId).map {
            CachedWebPage(url = it.url, title = it.title, text = it.text, fetchedAt = it.fetchedAt)
        }
    }

    override suspend fun cacheWebPage(chatId: String, url: String, title: String, text: String) {
        if (isIncognitoChat(chatId)) return  // не пишем кэш для приватных чатов
        webCache.upsert(
            WebPageCacheEntity(
                chatId = chatId,
                url = url,
                title = title,
                text = text,
                fetchedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun getEmbeddings(chatId: String): List<ChunkEmbedding> {
        if (isIncognitoChat(chatId)) return emptyList()
        return embeddings.listForChat(chatId).map {
            ChunkEmbedding(
                docId = it.docId,
                chunkId = it.chunkId,
                chunkText = it.chunkText,
                vector = VectorMath.fromBytes(java.util.Base64.getDecoder().decode(it.vector)),
                dims = it.dims,
            )
        }
    }

    override suspend fun saveEmbeddings(chatId: String, docId: String, items: List<ChunkEmbedding>) {
        if (isIncognitoChat(chatId) || items.isEmpty()) return  // private chats: don't persist
        val now = System.currentTimeMillis()
        embeddings.upsertAll(
            items.map {
                EmbeddingEntity(
                    chatId = chatId,
                    docId = docId,
                    chunkId = it.chunkId,
                    chunkText = it.chunkText,
                    vector = java.util.Base64.getEncoder().encodeToString(VectorMath.toBytes(it.vector)),
                    dims = it.dims,
                    createdAt = now,
                )
            }
        )
    }

    override suspend fun deleteEmbeddings(chatId: String, docId: String) {
        embeddings.deleteForDoc(chatId, docId)
    }
}
