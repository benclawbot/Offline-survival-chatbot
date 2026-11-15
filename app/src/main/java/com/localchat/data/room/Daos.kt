package com.localchat.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY pinned DESC, updated_at DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getById(id: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chat: ChatEntity)

    @Update
    suspend fun update(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE chats SET updated_at = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    @Query("UPDATE chats SET pinned = NOT pinned WHERE id = :id")
    suspend fun togglePin(id: String)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chats")
    suspend fun clearAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    fun observeForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    suspend fun listForChat(chatId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE chat_id = :chatId AND created_at >= (SELECT created_at FROM messages WHERE id = :fromId)")
    suspend fun deleteFromIdInclusive(chatId: String, fromId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}

@Dao
interface DocumentCacheDao {
    @Query("SELECT * FROM document_cache WHERE id = :id")
    suspend fun get(id: String): DocumentCacheEntity?

    @Query("SELECT * FROM document_cache WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<DocumentCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DocumentCacheEntity)

    @Query("DELETE FROM document_cache WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WebPageCacheDao {
    @Query("SELECT * FROM web_page_cache WHERE chat_id = :chatId AND url = :url")
    suspend fun get(chatId: String, url: String): WebPageCacheEntity?

    @Query("SELECT * FROM web_page_cache WHERE chat_id = :chatId ORDER BY fetched_at DESC")
    suspend fun listForChat(chatId: String): List<WebPageCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WebPageCacheEntity)

    @Query("DELETE FROM web_page_cache WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: String)
}

@Dao
interface EmbeddingDao {
    @Query("SELECT * FROM embeddings WHERE chat_id = :chatId")
    suspend fun listForChat(chatId: String): List<EmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE chat_id = :chatId AND doc_id = :docId")
    suspend fun deleteForDoc(chatId: String, docId: String)

    @Query("DELETE FROM embeddings WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: String)
}
