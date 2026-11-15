package com.localchat.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "localchat-prefs")

enum class ThemeMode { System, Light, Dark }


enum class AppLanguage { English, Russian, Spanish, ChineseSimplified, Hindi, Arabic }

const val DEFAULT_SYSTEM_PROMPT =
    "You are Survival Offline Chat, a private assistant running on this phone without internet. " +
        "Be helpful, concise, and practical; use the user's language. For survival, first-aid, medical, " +
        "emergency, or safety questions, search the offline Essentials reference library before answering, " +
        "ground the advice in retrieved passages, and name the source and URL. Never invent citations. " +
        "Say when the library does not answer. AI can be wrong; for immediate danger or serious symptoms, " +
        "seek local emergency help or a qualified professional."


data class AppSettings(
    val theme: ThemeMode = ThemeMode.Dark,
    val dynamicColor: Boolean = true,
    val incognitoDefault: Boolean = false,
    val language: AppLanguage = detectSystemLanguage(),
    val hfToken: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** Semantic-RAG (embeddings) on/off. When off, retrieval is lexical-only. */
    val semanticRagEnabled: Boolean = true,
    /** Id of the embedding model to use for semantic RAG ("" = first downloaded one). */
    val embeddingModelId: String = "",
)

@Serializable
data class PersistedModelConfig(
    val contextSize: Int,
    val maxTokens: Int,
    val topK: Int,
    val topP: Float,
    val temperature: Float,
    val accelerator: String,
)

@Singleton
class AppPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val INCOGNITO = booleanPreferencesKey("incognito_default")
        val LANGUAGE = stringPreferencesKey("language")
        val HF_TOKEN = stringPreferencesKey("hf_token")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val MODEL_CONFIGS = stringPreferencesKey("model_configs_json")
        val SEMANTIC_RAG = booleanPreferencesKey("semantic_rag_enabled")
        val EMBEDDING_MODEL = stringPreferencesKey("embedding_model_id")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val modelConfigMapSerializer = MapSerializer(String.serializer(), PersistedModelConfig.serializer())

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val language = p[Keys.LANGUAGE]
            ?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
            ?: detectSystemLanguage()
        AppSettings(
            theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.Dark,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            incognitoDefault = p[Keys.INCOGNITO] ?: false,
            language = language,
            hfToken = p[Keys.HF_TOKEN] ?: "",
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
            semanticRagEnabled = p[Keys.SEMANTIC_RAG] ?: true,
            embeddingModelId = p[Keys.EMBEDDING_MODEL] ?: "",
        )
    }

    val modelConfigs: Flow<Map<String, PersistedModelConfig>> = context.dataStore.data.map { p ->
        p[Keys.MODEL_CONFIGS]
            ?.let { runCatching { json.decodeFromString(modelConfigMapSerializer, it) }.getOrNull() }
            .orEmpty()
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC] = enabled }
    }

    suspend fun setIncognitoDefault(enabled: Boolean) {
        context.dataStore.edit { it[Keys.INCOGNITO] = enabled }
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = language.name
        }
    }

    suspend fun resetSystemPrompt() {
        context.dataStore.edit { prefs ->
            prefs[Keys.SYSTEM_PROMPT] = DEFAULT_SYSTEM_PROMPT
        }
    }

    suspend fun setHfToken(token: String) {
        context.dataStore.edit { it[Keys.HF_TOKEN] = token.trim() }
    }

    suspend fun setSystemPrompt(prompt: String) {
        context.dataStore.edit { it[Keys.SYSTEM_PROMPT] = prompt }
    }

    suspend fun setSemanticRagEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SEMANTIC_RAG] = enabled }
    }

    suspend fun setEmbeddingModelId(modelId: String) {
        context.dataStore.edit { it[Keys.EMBEDDING_MODEL] = modelId }
    }


    suspend fun setModelConfig(modelId: String, config: PersistedModelConfig) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.MODEL_CONFIGS]
                ?.let { runCatching { json.decodeFromString(modelConfigMapSerializer, it) }.getOrNull() }
                .orEmpty()
            prefs[Keys.MODEL_CONFIGS] = json.encodeToString(modelConfigMapSerializer, current + (modelId to config))
        }
    }
}

fun AppLanguage.displayName(): String = when (this) {
    AppLanguage.English -> "English"
    AppLanguage.Russian -> "Russian"
    AppLanguage.Spanish -> "Español"
    AppLanguage.ChineseSimplified -> "简体中文"
    AppLanguage.Hindi -> "हिन्दी"
    AppLanguage.Arabic -> "العربية"
}

private fun detectSystemLanguage(): AppLanguage = when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
    "ru" -> AppLanguage.Russian
    "es" -> AppLanguage.Spanish
    "zh" -> AppLanguage.ChineseSimplified
    "hi" -> AppLanguage.Hindi
    "ar" -> AppLanguage.Arabic
    else -> AppLanguage.English
}



