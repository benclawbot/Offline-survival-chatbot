package com.localchat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchat.data.prefs.AppPreferences
import com.localchat.data.prefs.AppSettings
import com.localchat.data.prefs.AppLanguage
import com.localchat.data.prefs.ThemeMode
import com.localchat.domain.model.ModelInfo
import com.localchat.domain.model.ModelKind
import com.localchat.domain.model.ModelState
import com.localchat.domain.repo.ChatRepository
import com.localchat.domain.repo.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val chatRepo: ChatRepository,
    private val modelRepo: ModelRepository,
) : ViewModel() {

    val settings = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Downloaded embedding models, for the active-model selector in the RAG settings. */
    val embeddingModels = modelRepo.observeStates()
        .map { states ->
            states.filter { it.info.kind == ModelKind.Embedding && (it is ModelState.Downloaded || it is ModelState.Loaded) }
                .map { it.info }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefs.setTheme(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { prefs.setDynamicColor(enabled) }
    fun setIncognitoDefault(enabled: Boolean) = viewModelScope.launch { prefs.setIncognitoDefault(enabled) }
    fun setLanguage(language: AppLanguage) = viewModelScope.launch { prefs.setLanguage(language) }
    fun resetSystemPrompt() = viewModelScope.launch { prefs.resetSystemPrompt() }
    fun setHfToken(token: String) = viewModelScope.launch { prefs.setHfToken(token) }
    fun setSystemPrompt(prompt: String) = viewModelScope.launch { prefs.setSystemPrompt(prompt) }
    fun setSemanticRag(enabled: Boolean) = viewModelScope.launch { prefs.setSemanticRagEnabled(enabled) }
    fun setEmbeddingModel(modelId: String) = viewModelScope.launch { prefs.setEmbeddingModelId(modelId) }

    fun clearChats() = viewModelScope.launch {
        runCatching { chatRepo.deleteAllChats() }
            .onSuccess { _toast.value = "История чатов очищена" }
            .onFailure { _toast.value = "Не удалось очистить чаты: ${it.message ?: "ошибка"}" }
    }

    fun deleteAllModels() = viewModelScope.launch {
        runCatching { modelRepo.deleteAll() }
            .onSuccess { _toast.value = "Модели удалены с устройства" }
            .onFailure { _toast.value = "Не удалось удалить модели: ${it.message ?: "ошибка"}" }
    }

    fun consumeToast() { _toast.value = null }
}


