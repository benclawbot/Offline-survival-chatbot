package com.localchat.data.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        DocumentCacheEntity::class,
        WebPageCacheEntity::class,
        EmbeddingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LocalChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun documentCacheDao(): DocumentCacheDao
    abstract fun webPageCacheDao(): WebPageCacheDao
    abstract fun embeddingDao(): EmbeddingDao
}
