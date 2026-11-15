package com.localchat.di

import android.content.Context
import androidx.room.Room
import com.localchat.data.model.FileModelRepository
import com.localchat.data.retrieval.EmbeddingGemmaEmbedder
import com.localchat.data.retrieval.TextEmbedder
import com.localchat.data.room.ChatDao
import com.localchat.data.room.DocumentCacheDao
import com.localchat.data.room.EmbeddingDao
import com.localchat.data.room.LocalChatDatabase
import com.localchat.data.room.MessageDao
import com.localchat.data.room.RoomChatRepository
import com.localchat.data.room.WebPageCacheDao
import com.localchat.data.search.DuckDuckGoSearch
import com.localchat.data.search.HttpClient
import com.localchat.data.search.HttpWebFetcher
import com.localchat.data.search.UrlHttpClient
import com.localchat.data.search.WebFetcher
import com.localchat.data.search.WebSearchRouter
import com.localchat.domain.repo.ChatRepository
import com.localchat.domain.repo.ModelRepository
import com.localchat.runtime.LiteRtRuntime
import com.localchat.runtime.LiteRtRuntimeImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindChatRepository(impl: RoomChatRepository): ChatRepository

    @Binds @Singleton
    abstract fun bindModelRepository(impl: FileModelRepository): ModelRepository

    @Binds @Singleton
    abstract fun bindRuntime(impl: LiteRtRuntimeImpl): LiteRtRuntime

    @Binds @Singleton
    abstract fun bindWebSearch(impl: DuckDuckGoSearch): WebSearchRouter

    @Binds @Singleton
    abstract fun bindHttpClient(impl: UrlHttpClient): HttpClient

    @Binds @Singleton
    abstract fun bindWebFetcher(impl: HttpWebFetcher): WebFetcher

    // Semantic embeddings via on-device EmbeddingGemma (multilingual). isReady is false until the
    // model files are downloaded, so the retriever transparently falls back to lexical-only.
    @Binds @Singleton
    abstract fun bindTextEmbedder(impl: EmbeddingGemmaEmbedder): TextEmbedder
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LocalChatDatabase =
        Room.databaseBuilder(context, LocalChatDatabase::class.java, "localchat.db")
            // Single schema (v1); no released version to migrate from. A schema change during
            // development simply recreates the DB.
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides fun provideChatDao(db: LocalChatDatabase): ChatDao = db.chatDao()
    @Provides fun provideMessageDao(db: LocalChatDatabase): MessageDao = db.messageDao()
    @Provides fun provideDocumentCacheDao(db: LocalChatDatabase): DocumentCacheDao = db.documentCacheDao()
    @Provides fun provideWebPageCacheDao(db: LocalChatDatabase): WebPageCacheDao = db.webPageCacheDao()
    @Provides fun provideEmbeddingDao(db: LocalChatDatabase): EmbeddingDao = db.embeddingDao()
}


