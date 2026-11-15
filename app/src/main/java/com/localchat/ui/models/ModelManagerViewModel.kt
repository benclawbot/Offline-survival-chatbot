package com.localchat.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchat.domain.model.Accelerator
import com.localchat.domain.model.ModelState
import com.localchat.domain.repo.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val repo: ModelRepository,
) : ViewModel() {

    val states = repo.observeStates()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<ModelState>())

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()
    fun consumeToast() { _toast.value = null }

    init {
        viewModelScope.launch {
            val bundled = "qwen2-0-5b-instruct"
            states.first { items -> items.any { it.info.id == bundled && it is ModelState.Downloaded } }
            runCatching { repo.load(bundled) }.onFailure {
                _toast.value = "Could not load the offline model: ${it.message ?: "error"}"
            }
        }
    }

    fun load(id: String) = viewModelScope.launch {
        runCatching { repo.load(id) }.onFailure {
            _toast.value = "Could not load: ${it.message ?: "error"}"
        }
    }
    fun unload() = viewModelScope.launch { runCatching { repo.unload() } }
    fun startDownload(id: String) = viewModelScope.launch {
        runCatching { repo.startDownload(id) }.onFailure {
            _toast.value = "Download failed: ${it.message ?: "error"}"
        }
    }
    fun cancelDownload(id: String) = viewModelScope.launch { runCatching { repo.cancelDownload(id) } }
    fun delete(id: String) = viewModelScope.launch { runCatching { repo.delete(id) } }
    fun deleteAll() = viewModelScope.launch {
        runCatching { repo.deleteAll() }.onFailure {
            _toast.value = "Could not delete models: ${it.message ?: "error"}"
        }
    }

    fun updateConfig(
        modelId: String,
        contextSize: Int,
        maxTokens: Int,
        topK: Int,
        topP: Float,
        temperature: Float,
        accelerator: Accelerator,
        enableSpeculative: Boolean,
        reload: Boolean = false,
    ) = viewModelScope.launch {
        runCatching {
            repo.updateConfig(modelId, contextSize, maxTokens, topK, topP, temperature, accelerator, enableSpeculative)
        }.onFailure {
            _toast.value = "Could not save: ${it.message ?: "error"}"
        }
    }

    fun addCustom(
        repoId: String,
        file: String,
        displayName: String,
        vision: Boolean,
        thinking: Boolean,
        contextSize: Int,
        accelerator: Accelerator,
    ) = viewModelScope.launch {
        repo.addCustom(repoId, file, displayName, vision, thinking, contextSize, accelerator)
    }
}
