package com.localchat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localchat.data.documents.DocumentTextExtractor
import com.localchat.data.documents.NativePdfRenderer
import com.localchat.data.documents.PdfBoxPdfDocument
import com.localchat.data.documents.PdfIngestOptions
import com.localchat.data.documents.PdfIngestor
import com.localchat.data.prefs.AppPreferences
import com.localchat.data.prefs.AppSettings
import com.localchat.data.retrieval.LexicalRetriever
import com.localchat.data.search.WebFetcher
import com.localchat.data.search.WebSearchRouter
import com.localchat.domain.chat.Attachment
import com.localchat.domain.chat.Message
import com.localchat.domain.chat.Role
import com.localchat.domain.chat.ToolPart
import com.localchat.domain.repo.ChatRepository
import com.localchat.domain.repo.ModelRepository
import com.localchat.runtime.AgentEvent
import com.localchat.runtime.AgentOrchestrator
import com.localchat.runtime.SurvivalLibrarySearch
import com.localchat.runtime.ContextWindow
import com.localchat.runtime.LiteRtRuntime
import com.localchat.runtime.RuntimeChatMessage
import com.localchat.runtime.RuntimeChatRole
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import com.localchat.runtime.AgentTools2
import com.localchat.runtime.ToolDialect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatRepo: ChatRepository,
    private val modelRepo: ModelRepository,
    private val runtime: LiteRtRuntime,
    private val webSearch: WebSearchRouter,
    private val webFetcher: WebFetcher,
    private val survivalLibrary: SurvivalLibrarySearch,
    private val retriever: LexicalRetriever,
    private val embedder: com.localchat.data.retrieval.TextEmbedder,
    prefs: AppPreferences,
) : ViewModel() {

    private val activeChatId = MutableStateFlow<String?>(null)
    val activeChatIdFlow = activeChatId.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    fun consumeToast() { _toast.value = null }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()

    val chats = chatRepo.observeChats()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val active = modelRepo.observeActive()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val modelStates = modelRepo.observeStates()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    @Suppress("OPT_IN_USAGE")
    val messages = activeChatId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else chatRepo.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _composer = MutableStateFlow(ComposerState())
    val composer = _composer.asStateFlow()

    /**
     * Full context-window usage for the top meter — counts EVERYTHING the model receives:
     * system prompt + tools header + chat history + draft + image reserve + inlined
     * document RAG. Not just the visible message text.
     */
    // Trigger flow: probe results + retriever corpus version. Neither value is used directly —
    // computeContextUsage reads runtime.capabilities.value and retriever.indexedTokens itself —
    // but emitting here makes the meter recompute when the bundle is probed AND after async
    // (re)indexing changes the document RAG token count.
    private val contextTriggers = combine(runtime.capabilities, retriever.version) { _, _ -> }

    val contextUsage: StateFlow<ContextUsage> =
        combine(active, messages, _composer, settings, contextTriggers) { active, msgs, composer, settings, _ ->
            computeContextUsage(active?.info, msgs, composer, settings)
        }
            .flowOn(Dispatchers.Default) // indexedTokens iterates the corpus — keep it off Main
            .stateIn(viewModelScope, SharingStarted.Eagerly, ContextUsage(0, DEFAULT_CONTEXT_TOKENS, 0f))

    /** Per-document processing state, surfaced to the composer's attachment chip (spinner). */
    enum class DocStatus { Extracting, Embedding, Ready, Failed }
    private val _docStatus = MutableStateFlow<Map<String, DocStatus>>(emptyMap())
    val docStatus: StateFlow<Map<String, DocStatus>> = _docStatus.asStateFlow()
    private fun setDocStatus(docId: String, status: DocStatus) {
        _docStatus.value = _docStatus.value + (docId to status)
    }

    /**
     * Whether the active model can actually see images — the PROBED encoder result when the
     * loaded bundle has been measured, otherwise the declared flag. The composer's image
     * button binds to this so it disables itself on a text-only bundle.
     */
    val visionSupported: StateFlow<Boolean> =
        combine(active, runtime.capabilities) { active, caps ->
            if (caps != null && caps.modelId == active?.info?.id) caps.visionEnabled
            else active?.info?.supportsVision == true
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Тикает на каждый send() — UI слушает и принудительно скроллит вниз. */
    private val _sendTrigger = MutableStateFlow(0L)
    val sendTrigger = _sendTrigger.asStateFlow()

    private var streamJob: Job? = null
    private var currentChatForRuntime: String? = null
    private var currentAgent: AgentOrchestrator? = null

    fun openChat(id: String?) {
        if (activeChatId.value == id) return
        // Stop any in-flight generation before switching — otherwise resetRuntimeConversation
        // below would replace the conversation out from under an active stream.
        if (_isStreaming.value) stop()
        // Reset the composer's draft + attachments so the previous chat's input/files don't bleed
        // into the new chat or its context meter (keep the web/think toggles). Clear doc statuses.
        _composer.value = _composer.value.copy(input = "", attachments = emptyList())
        _docStatus.value = emptyMap()
        activeChatId.value = id
        // Сменили чат — пересобрать контекст модели из истории выбранного чата.
        if (id != currentChatForRuntime) {
            // Retriever скоупится по чату: при переключении сбрасываем корпус,
            // чтобы документы и веб-страницы из соседнего диалога не утекали в
            // retriever_search текущего.
            retriever.clear()
            pageRenderCache.clear()
            viewModelScope.launch {
                runCatching { resetRuntimeConversation(id) }
                if (id != null) {
                    rehydrateWebCache(id)
                    reindexAttachedDocuments(id)
                }
            }
            currentChatForRuntime = id
        }
    }

    fun renameChat(id: String, title: String) = viewModelScope.launch {
        chatRepo.renameChat(id, title)
    }

    fun deleteChat(id: String) = viewModelScope.launch {
        if (activeChatId.value == id) activeChatId.value = null
        if (currentChatForRuntime == id) {
            runCatching { resetRuntimeConversation() }
            currentChatForRuntime = null
        }
        chatRepo.deleteChat(id)
    }

    fun toggleWebSearch() {
        _toast.value = "This build works fully offline. Local library search is always on."
    }

    fun toggleThinking() {
        val info = active.value?.info
        if (info?.supportsThinking != true) {
            _toast.value = if (info == null) "Load a model first" else "This model does not support thinking"
            return
        }
        _composer.value = _composer.value.copy(thinking = !_composer.value.thinking)
    }

    fun updateInput(text: String) {
        _composer.value = _composer.value.copy(input = text)
    }

    fun appendRecognizedText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val current = _composer.value.input
        val separator = if (current.isBlank() || current.endsWith(" ") || current.endsWith("\n")) "" else " "
        _composer.value = _composer.value.copy(input = current + separator + clean)
    }

    fun addAttachment(att: Attachment) {
        if (att is Attachment.Image && !effectiveVision(active.value?.info)) {
            _toast.value = "Images require a vision model"
            return
        }
        if (att is Attachment.Document && active.value == null) {
            _toast.value = "Load a model first"
            return
        }
        _composer.value = _composer.value.copy(attachments = _composer.value.attachments + att)
        if (att is Attachment.Document) {
            // Pre-warm extraction off the main thread so send() isn't stalled later, and surface a
            // status so the composer chip can show a spinner instead of the UI appearing frozen.
            setDocStatus(att.id, DocStatus.Extracting)
            viewModelScope.launch(Dispatchers.IO) {
                val text = cachedOrExtractDocumentText(att)
                setDocStatus(att.id, if (text.isNullOrBlank()) DocStatus.Failed else DocStatus.Ready)
            }
        }
    }

    fun removeAttachment(id: String) {
        _composer.value = _composer.value.copy(attachments = _composer.value.attachments.filterNot { it.id == id })
    }

    fun stop() {
        currentAgent?.cancel()
        runtime.stop()
        streamJob?.cancel()
        // Снимаем флаг стрима сразу — UI не должен ждать пока орхестратор раскрутится.
        _isStreaming.value = false
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        val chatId = activeChatId.value
        val currentMessages = chatId?.let { chatRepo.getMessages(it) }.orEmpty()
        val target = currentMessages.firstOrNull { it.id == messageId }
        val idsToDelete = linkedSetOf(messageId)
        if (target != null && target.role == Role.Assistant && target.tool == null) {
            val idx = currentMessages.indexOfFirst { it.id == messageId }
            var i = idx - 1
            while (i >= 0) {
                val prev = currentMessages[i]
                if (prev.role == Role.Assistant && prev.tool != null) {
                    idsToDelete.add(prev.id)
                    i--
                } else break
            }
        }
        idsToDelete.forEach { chatRepo.deleteMessage(it) }
    }

    fun editMessage(messageId: String, newText: String, attachments: List<Attachment>, regenerate: Boolean = false) = viewModelScope.launch {
        val msgs = messages.value
        val msg = msgs.firstOrNull { it.id == messageId } ?: return@launch
        chatRepo.updateMessage(msg.copy(text = newText, attachments = attachments))
        if (regenerate && msg.role == Role.User) {
            regenerateFrom(messageId)
        }
    }

    fun addAttachmentToMessage(messageId: String, attachment: Attachment) = viewModelScope.launch {
        val msg = messages.value.firstOrNull { it.id == messageId } ?: return@launch
        chatRepo.updateMessage(msg.copy(attachments = msg.attachments + attachment))
    }

    fun removeMessageAttachment(messageId: String, attachmentId: String) = viewModelScope.launch {
        val msg = messages.value.firstOrNull { it.id == messageId } ?: return@launch
        chatRepo.updateMessage(msg.copy(attachments = msg.attachments.filterNot { it.id == attachmentId }))
    }

    fun regenerateFrom(messageId: String) {
        if (active.value == null) {
            _toast.value = "Load a model first"
            return
        }
        if (_composer.value.thinking && active.value?.info?.supportsThinking != true) {
            _toast.value = "This model does not support thinking"
            return
        }
        if (_isStreaming.value) return

        viewModelScope.launch {
            val chatId = activeChatId.value ?: return@launch
            val composerState = _composer.value
            // Читаем напрямую из репозитория — StateFlow `messages` может отставать
            // на тик после editMessage.upsert(...) из-за асинхронного Flow Room'а.
            val msgs = chatRepo.getMessages(chatId)
            val target = msgs.firstOrNull { it.id == messageId } ?: return@launch

            val targetIdx = msgs.indexOf(target)
            val userIdx = (targetIdx downTo 0).firstOrNull { msgs[it].role == Role.User } ?: return@launch
            val userMsg = msgs[userIdx]

            chatRepo.deleteMessagesAfter(chatId, userMsg.id)
            chatRepo.appendMessage(userMsg)
            currentChatForRuntime = chatId

            val historyBeforePrompt = msgs.take(userIdx).toRuntimeHistory()

            val placeholder = Message(
                id = UUID.randomUUID().toString(), chatId = chatId, role = Role.Assistant,
                text = "", isStreaming = true,
            )
            chatRepo.appendMessage(placeholder)

            _isStreaming.value = true
            streamJob = launch {
                val prepared = preparePromptAndImages(
                    text = userMsg.text,
                    attachments = userMsg.attachments,
                    chatDocs = chatDocuments(chatId),
                )
                rehydrateWebCache(chatId)
                reindexAttachedDocuments(chatId)
                val agentRagContext = buildAgentRagContext(chatId, prepared.ragContext)
                runAgentStream(
                    userPrompt = userMsg.text,
                    ragContext = agentRagContext,
                    images = prepared.images,
                    enableThinking = composerState.thinking,
                    placeholder = placeholder,
                    historyBeforePrompt = historyBeforePrompt,
                    webSearchEnabled = composerState.webSearch,
                )
            }
        }
    }

    private suspend fun runAgentStream(
        userPrompt: String,
        ragContext: String,
        images: List<ByteArray>,
        enableThinking: Boolean,
        placeholder: Message,
        historyBeforePrompt: List<RuntimeChatMessage>,
        webSearchEnabled: Boolean,
    ) {
        // The orchestrator reseeds the conversation (with trimmed history) on every
        // turn, so no pre-reset is needed here.
        val chatId = placeholder.chatId
        // Embed this chat's documents in the background (no-op until the embedder is ready):
        // lexical search works now, semantic augments once vectors land.
        launchEmbedPending(chatId)
        // Текущий "открытый" assistant text-bubble — placeholder в начале, потом
        // создаём новые после каждого tool_result.
        var current: Message = placeholder
        var currentIsTextBubble = true
        val currentText = StringBuilder()
        val currentThinking = StringBuilder()
        // Thinking timer: starts on the first thought token of the current bubble and
        // grows live (each flush writes it) so the UI can show "Thinking (Xс)"; the final
        // value persists as "Thought for X".
        var thinkStartedAt = 0L
        var thinkingMs: Long? = null
        var finalized = false

        suspend fun flushCurrent(streaming: Boolean) {
            chatRepo.updateMessage(
                current.copy(
                    text = currentText.toString(),
                    thinking = currentThinking.toString().trim().takeIf { it.isNotEmpty() },
                    thinkingMs = thinkingMs,
                    isStreaming = streaming,
                )
            )
        }

        suspend fun openNewBubble() {
            // Закрываем текущий, если в нём что-то есть, иначе удаляем пустой плейсхолдер.
            if (currentIsTextBubble) {
                if (currentText.isEmpty() && currentThinking.isEmpty()) {
                    chatRepo.deleteMessage(current.id)
                } else {
                    flushCurrent(streaming = false)
                }
            }
            currentText.clear()
            currentThinking.clear()
            thinkStartedAt = 0L
            thinkingMs = null
            val next = Message(
                id = UUID.randomUUID().toString(),
                chatId = chatId,
                role = Role.Assistant,
                text = "",
                isStreaming = true,
            )
            chatRepo.appendMessage(next)
            current = next
            currentIsTextBubble = true
        }

        try {
            // Models that don't follow the tool protocol get NO tools — they answer
            // directly from the (already-injected) context instead of botching tool calls.
            val toolCapable = active.value?.info?.supportsTools ?: true
            val libraryContext = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val hits = survivalLibrary.search(userPrompt, 3)
                    if (hits.isEmpty()) "No matching passages found in the offline library. Say so and do not invent sources."
                    else hits.joinToString("\n\n", "Offline library passages (cite the source title and URL):\n") {
                        "Source: ${it.title} (${it.source})\nURL: ${it.url}\n${it.text.take(1200)}"
                    }
                } catch (e: Exception) {
                    "Offline library search failed. Tell the user the library could not be searched; do not invent citations."
                }
            }
            val agent = AgentOrchestrator(
                runtime = runtime,
                webSearch = webSearch,
                fetcher = webFetcher,
                systemPrompt = settings.value.systemPrompt,
                webToolsEnabled = false,
                retriever = if (toolCapable) retriever else null,
                survivalLibrary = if (toolCapable) survivalLibrary else null,
                onFetchedPage = { page ->
                    chatRepo.cacheWebPage(chatId, page.url, page.title, page.text)
                },
                contextBudgetTokens = active.value?.info?.let {
                    ContextWindow.inputBudget(it.contextSize, it.maxTokens) - 512
                } ?: 8_000,
                dialect = effectiveDialect(active.value?.info),
                embedQuery = if (toolCapable && embedder.isReady) { q -> embedQuery(q) } else null,
            )
            currentAgent = agent
            agent.run(
                userPrompt = userPrompt,
                ragContext = listOf(ragContext, libraryContext).filter { it.isNotBlank() }.joinToString("\n\n"),
                images = images,
                enableThinking = enableThinking,
                history = trimToContext(historyBeforePrompt),
            ).collect { event ->
                when (event) {
                    AgentEvent.TextStart -> {
                        // Первое TextStart приходит на старте — placeholder уже есть, не открываем
                        // новый. На последующих — открываем.
                        if (currentText.isNotEmpty() || currentThinking.isNotEmpty() || current.id != placeholder.id) {
                            openNewBubble()
                        }
                    }
                    is AgentEvent.TextDelta -> {
                        if (!currentIsTextBubble) openNewBubble()
                        currentText.append(event.text)
                        flushCurrent(streaming = true)
                    }
                    is AgentEvent.ThinkingDelta -> {
                        if (!currentIsTextBubble) openNewBubble()
                        if (thinkStartedAt == 0L) thinkStartedAt = System.currentTimeMillis()
                        thinkingMs = System.currentTimeMillis() - thinkStartedAt
                        currentThinking.append(event.text)
                        flushCurrent(streaming = true)
                    }
                    is AgentEvent.ToolCallStarted -> {
                        // Hide short pre-tool prose ("I will search...", "checking...").
                        // Tool cards carry the trace; final answer appears after tools finish.
                        if (currentIsTextBubble) {
                            chatRepo.deleteMessage(current.id)
                        }
                        // Card for tool_call.
                        val callMsg = Message(
                            id = UUID.randomUUID().toString(),
                            chatId = chatId,
                            role = Role.Assistant,
                            text = "",
                            isStreaming = false,
                            tool = ToolPart.Call(event.name, event.argumentsJson),
                        )
                        chatRepo.appendMessage(callMsg)
                        currentText.clear()
                        currentThinking.clear()
                        current = callMsg
                        currentIsTextBubble = false
                    }
                    is AgentEvent.ToolCallFinished -> {
                        val resMsg = Message(
                            id = UUID.randomUUID().toString(),
                            chatId = chatId,
                            role = Role.Assistant,
                            text = "",
                            isStreaming = false,
                            tool = ToolPart.Result(
                                name = event.name,
                                ok = event.ok,
                                summary = event.summary,
                                payload = event.payload,
                            ),
                        )
                        chatRepo.appendMessage(resMsg)
                        // current stays on the tool-result card; next TextStart opens a text bubble.
                        current = resMsg
                        currentIsTextBubble = false
                        currentText.clear()
                        currentThinking.clear()
                    }
                    is AgentEvent.Done -> {
                        // Закрываем последний открытый text-bubble с метриками; пустой удаляем.
                        if (currentIsTextBubble && currentText.isEmpty() && currentThinking.isEmpty()) {
                            chatRepo.deleteMessage(current.id)
                        } else if (currentIsTextBubble) {
                            chatRepo.updateMessage(
                                current.copy(
                                    text = currentText.toString(),
                                    thinking = currentThinking.toString().trim().takeIf { it.isNotEmpty() },
                                    thinkingMs = thinkingMs,
                                    latencyMs = event.metrics.firstTokenLatencyMs,
                                    tokenCount = event.metrics.tokenCount,
                                    tokensPerSec = event.metrics.tokensPerSec,
                                    isStreaming = false,
                                )
                            )
                        }
                        finalized = true
                    }
                    is AgentEvent.Error -> {
                        if (!currentIsTextBubble) openNewBubble()
                        if (currentText.isEmpty()) currentText.append("Error: ${event.message}")
                        else currentText.append("\n\n[error: ${event.message}]")
                        flushCurrent(streaming = false)
                        finalized = true
                    }
                }
            }
        } finally {
            if (!finalized) {
                if (currentIsTextBubble) {
                    if (currentText.isEmpty() && currentThinking.isEmpty()) {
                        chatRepo.deleteMessage(current.id)
                    } else {
                        flushCurrent(streaming = false)
                    }
                }
            }
            currentAgent = null
            _isStreaming.value = false
        }
    }

    private suspend fun resetRuntimeConversation(chatId: String? = activeChatId.value) {
        val prompt = settings.value.systemPrompt
        val historyAll = chatId?.let { chatRepo.getMessages(it).toRuntimeHistory() } ?: emptyList()
        val history = trimToContext(historyAll)
        runtime.resetConversation(prompt, history)
    }

    /**
     * Re-index every user-attached document for [chatId] into the lexical retriever.
     * Pulls cached extracted text from Room first — `content://` URIs may be revoked
     * after process restart, but the cached text survives. Falls back to live
     * extraction only if the cache is empty.
     */
    private suspend fun reindexAttachedDocuments(chatId: String) = withContext(Dispatchers.IO) {
        val msgs = runCatching { chatRepo.getMessages(chatId) }.getOrDefault(emptyList())
        val docs = msgs.flatMap { m -> m.attachments.filterIsInstance<Attachment.Document>() }
            .distinctBy { it.id }
        for (doc in docs) {
            val text = cachedOrExtractDocumentText(doc) ?: continue
            retriever.indexDocument(docId = retrieverDocId(doc), docTitle = doc.name, text = text)
        }
        // Rehydrate persisted vectors (avoid re-embedding) so semantic search works immediately.
        val stored = runCatching { chatRepo.getEmbeddings(chatId) }.getOrDefault(emptyList())
        if (stored.isNotEmpty()) retriever.putVectors(stored.associate { it.chunkId to it.vector })
        // Embed any doc that has no stored vectors yet (e.g. model downloaded after attach).
        for (doc in docs) embedDocument(chatId, doc)
    }

    /**
     * Embed a document's chunks (DOCUMENT kind) → attach vectors to the retriever and persist
     * them per chat. No-op when the embedder isn't ready (lexical-only) or vectors already exist.
     */
    private suspend fun embedDocument(chatId: String, doc: Attachment.Document) {
        if (!embedder.isReady) return
        val docId = retrieverDocId(doc)
        if (retriever.hasVectors(docId)) return
        val entries = retriever.chunkEntries(docId)
        if (entries.isEmpty()) return
        setDocStatus(doc.id, DocStatus.Embedding)
        val vectors = runCatching {
            embedder.embed(entries.map { it.second }, com.localchat.data.retrieval.EmbedKind.DOCUMENT)
        }.getOrNull()
        if (vectors == null || vectors.size != entries.size) {
            setDocStatus(doc.id, DocStatus.Ready)
            return
        }
        val byId = entries.mapIndexed { i, (cid, _) -> cid to vectors[i] }.toMap()
        retriever.putVectors(byId)
        runCatching {
            chatRepo.saveEmbeddings(
                chatId, docId,
                entries.mapIndexed { i, (cid, txt) ->
                    com.localchat.domain.repo.ChunkEmbedding(docId, cid, txt, vectors[i], embedder.dims)
                },
            )
        }
        setDocStatus(doc.id, DocStatus.Ready)
    }

    /** Background-embed every document in [chatId] that still lacks vectors. No-op if not ready. */
    private fun launchEmbedPending(chatId: String) {
        if (!embedder.isReady) return
        viewModelScope.launch(Dispatchers.Default) {
            val msgs = runCatching { chatRepo.getMessages(chatId) }.getOrDefault(emptyList())
            msgs.flatMap { it.attachments.filterIsInstance<Attachment.Document>() }
                .distinctBy { it.id }
                .forEach { embedDocument(chatId, it) }
        }
    }

    /** Embed a query string (QUERY kind) for semantic retrieval; null when embedder not ready. */
    private suspend fun embedQuery(text: String): FloatArray? {
        if (!embedder.isReady) return null
        return runCatching {
            embedder.embed(listOf(text), com.localchat.data.retrieval.EmbedKind.QUERY).firstOrNull()
        }.getOrNull()
    }


    /**
     * Pre-load cached web pages for the chat into the retriever so the agent
     * can call retriever_search across them right away — no need to refetch.
     */
    private suspend fun rehydrateWebCache(chatId: String) = withContext(Dispatchers.IO) {
        val pages = runCatching { chatRepo.getCachedWebPages(chatId) }.getOrDefault(emptyList())
        for (page in pages) {
            retriever.indexDocument(
                docId = page.url,
                docTitle = page.title.ifBlank { page.url },
                text = page.text,
            )
        }
    }

    private suspend fun buildAgentRagContext(chatId: String, currentRagContext: String): String {
        val inventory = buildKnowledgeInventory(chatId)
        return listOf(currentRagContext, inventory)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private suspend fun buildKnowledgeInventory(chatId: String): String = withContext(Dispatchers.IO) {
        val msgs = runCatching { chatRepo.getMessages(chatId) }.getOrDefault(emptyList())
        val docs = msgs.flatMap { m -> m.attachments.filterIsInstance<Attachment.Document>() }
            .distinctBy { it.id }
        val pages = runCatching { chatRepo.getCachedWebPages(chatId) }.getOrDefault(emptyList())
        if (docs.isEmpty() && pages.isEmpty()) return@withContext ""

        // Inline the attached files' text EVERY turn (sticky across the whole chat), not just on
        // the turn they were attached — so follow-ups ("реши задание 1", "ну в документе") still
        // have the content. Short docs fit whole; long ones are capped + reachable via search_files.
        val withText = docs.map { it to chatRepo.getCachedDocumentText(it.id) }
            .filter { !it.second.isNullOrBlank() }
        val queued = docs.filter { d -> withText.none { it.first.id == d.id } }
        val perDocChars = attachedDocCharBudget(active.value?.info, withText.size.coerceAtLeast(1))

        buildString {
            append("[ATTACHED FILES — their content is below. The user's questions are almost always ABOUT ")
            append("these files. When the user says \"the document\", \"this\", \"task\"/\"exercise N\", ")
            append("\"summarize\", etc., ANSWER DIRECTLY FROM THIS CONTENT. Do NOT ask the user to ")
            append("paste or restate text that is already here — read it. For parts beyond an excerpt, call ")
            append("search_files(\"<filename>\"). If page images are attached, also read those.]\n")
            withText.forEach { (doc, content) ->
                val body = content!!.trim()
                append("\n=== ").append(doc.name).append(" ===\n")
                append(body.take(perDocChars))
                if (body.length > perDocChars) {
                    append("\n…[truncated — use search_files(\"").append(doc.name).append("\") for the rest]")
                }
                append('\n')
            }
            if (queued.isNotEmpty()) {
                append("\nFiles still being processed: ")
                append(queued.joinToString(", ") { it.name }).append('\n')
            }
            if (pages.isNotEmpty()) {
                append("\nPreviously opened web pages (use search_files to read their content):\n")
                pages.take(12).forEach { page ->
                    append("- ").append(page.title.ifBlank { page.url }).append(" — ").append(page.url).append('\n')
                }
                if (pages.size > 12) append("- ...and ").append(pages.size - 12).append(" more").append('\n')
            }
            append("[/ATTACHED FILES]")
        }
    }

    private suspend fun cachedOrExtractDocumentText(doc: Attachment.Document): String? {
        chatRepo.getCachedDocumentText(doc.id)?.let { return it }
        val extracted = readDocumentText(doc) ?: return null
        chatRepo.cacheDocumentText(doc.id, doc.name, extracted)
        return extracted
    }

    /** All distinct documents attached anywhere in [chatId] — the chat's "sticky" file set. */
    private suspend fun chatDocuments(chatId: String): List<Attachment.Document> =
        runCatching { chatRepo.getMessages(chatId) }.getOrDefault(emptyList())
            .flatMap { it.attachments.filterIsInstance<Attachment.Document>() }
            .distinctBy { it.id }

    private fun retrieverDocId(doc: Attachment.Document): String = "file:${doc.name}:${doc.id}"

    private fun trimToContext(history: List<RuntimeChatMessage>): List<RuntimeChatMessage> {
        val info = active.value?.info ?: return history
        return ContextWindow.trimHistory(history, info.contextSize, info.maxTokens)
    }

    // Memo so the tools-protocol string isn't rebuilt on every keystroke (it only depends
    // on the model + web toggle, not the draft).
    // ── Effective capabilities ─────────────────────────────────────────────────────────────
    // Prefer what the runtime PROBED on the actual bundle over ModelInfo's heuristic flags.
    // A bundle may advertise a family (Gemma = vision) yet ship without the encoder, so we
    // never trust the declared flag once the engine has told us the truth for this model.

    /** True if the loaded bundle actually encodes images (probed), else the declared flag. */
    private fun effectiveVision(info: com.localchat.domain.model.ModelInfo?): Boolean {
        val caps = runtime.capabilities.value
        return if (caps != null && caps.modelId == info?.id) caps.visionEnabled
        else info?.supportsVision == true
    }

    /** Tool dialect from the engine-reported template family (probed), else the name guess. */
    private fun effectiveDialect(info: com.localchat.domain.model.ModelInfo?): ToolDialect {
        val caps = runtime.capabilities.value
        return when {
            caps != null && caps.modelId == info?.id -> ToolDialect.forFamily(caps.templateFamily)
            info != null -> ToolDialect.forModel(info.id, info.displayName)
            else -> ToolDialect.Hermes
        }
    }

    private var toolsTokenMemo: Pair<String, Int>? = null
    private fun toolsHeaderTokens(info: com.localchat.domain.model.ModelInfo, webSearch: Boolean): Int {
        // Family is part of the key: when the probe lands (dialect may switch from the name
        // guess), the memo recomputes instead of serving a stale header size.
        val key = "${info.id}|$webSearch|${runtime.capabilities.value?.templateFamily}"
        toolsTokenMemo?.let { if (it.first == key) return it.second }
        val tools = AgentTools2.available(webToolsEnabled = webSearch, retrieverEnabled = true)
        val tokens = ContextWindow.estimateTokens(effectiveDialect(info).protocol(tools))
        toolsTokenMemo = key to tokens
        return tokens
    }

    /** Estimate the FULL input context the model receives (see [contextUsage]). */
    private fun computeContextUsage(
        info: com.localchat.domain.model.ModelInfo?,
        msgs: List<Message>,
        composer: ComposerState,
        settings: AppSettings,
    ): ContextUsage {
        val total = (info?.contextSize ?: DEFAULT_CONTEXT_TOKENS).coerceAtLeast(256)
        if (info == null) return ContextUsage(0, total, 0f)
        val overhead = ContextWindow.PER_MESSAGE_OVERHEAD
        fun est(s: String) = ContextWindow.estimateTokens(s)

        val systemTok = est(settings.systemPrompt) + overhead
        val toolsTok = if (info.supportsTools) toolsHeaderTokens(info, composer.webSearch) else 0
        val historyTok = msgs.filterNot { it.isStreaming }.sumOf { est(it.text) + overhead }
        val draftTok = if (composer.input.isBlank() && composer.attachments.isEmpty()) 0
        else est(composer.input) + overhead
        val imageTok = if (effectiveVision(info))
            composer.attachments.count { it is Attachment.Image } * ContextWindow.IMAGE_TOKEN_RESERVE else 0
        val docNameTok = est(composer.attachments.filterIsInstance<Attachment.Document>().joinToString(" ") { it.name })
        // Inlined document RAG ≈ indexed file-doc tokens, capped at the per-send doc budget.
        val docRagTok = if (info.supportsTools) {
            val budget = (ContextWindow.inputBudget(info.contextSize, info.maxTokens) * 0.55).toInt().coerceIn(500, 6_000)
            retriever.indexedTokens { it.startsWith("file:") }.coerceAtMost(budget)
        } else 0

        val used = (systemTok + toolsTok + historyTok + draftTok + imageTok + docNameTok + docRagTok)
            .coerceIn(0, total)
        return ContextUsage(usedTokens = used, totalTokens = total, progress = used / total.toFloat())
    }

    fun send() {
        val composerState = _composer.value
        val text = composerState.input.trim()
        if (text.isEmpty() && composerState.attachments.isEmpty()) return
        if (active.value == null) {
            _toast.value = "Load a model first"
            return
        }
        if (composerState.attachments.any { it is Attachment.Image } && !effectiveVision(active.value?.info)) {
            _toast.value = "The selected model does not support images"
            return
        }
        if (composerState.thinking && active.value?.info?.supportsThinking != true) {
            _toast.value = "This model does not support thinking"
            return
        }
        if (_isStreaming.value) return

        viewModelScope.launch {
            val incognito = settings.value.incognitoDefault
            val chatId = activeChatId.value
                ?: chatRepo.createChat(
                    title = text.take(40).ifEmpty { "New chat" },
                    incognito = incognito,
                ).also {
                    activeChatId.value = it.id
                    currentChatForRuntime = it.id
                }.id
            val historyBeforePrompt = chatRepo.getMessages(chatId).toRuntimeHistory()

            val userMsg = Message(
                id = UUID.randomUUID().toString(), chatId = chatId, role = Role.User,
                text = text, attachments = composerState.attachments,
            )
            chatRepo.appendMessage(userMsg)
            _composer.value = composerState.copy(input = "", attachments = emptyList())
            _sendTrigger.value = System.currentTimeMillis()

            val placeholder = Message(
                id = UUID.randomUUID().toString(), chatId = chatId, role = Role.Assistant,
                text = "", isStreaming = true,
            )
            chatRepo.appendMessage(placeholder)

            _isStreaming.value = true
            streamJob = launch {
                val prepared = preparePromptAndImages(
                    text = text,
                    attachments = composerState.attachments,
                    chatDocs = chatDocuments(chatId),
                )
                rehydrateWebCache(chatId)
                reindexAttachedDocuments(chatId)
                val agentRagContext = buildAgentRagContext(chatId, prepared.ragContext)
                // Always-on agent: the model decides whether to use tools. The composer
                // This build is offline: local library and attached file search remain available.
                runAgentStream(
                    userPrompt = text,
                    ragContext = agentRagContext,
                    images = prepared.images,
                    enableThinking = composerState.thinking,
                    placeholder = placeholder,
                    historyBeforePrompt = historyBeforePrompt,
                    webSearchEnabled = composerState.webSearch,
                )
            }
        }
    }


    private suspend fun preparePromptAndImages(
        text: String,
        attachments: List<Attachment>,
        chatDocs: List<Attachment.Document>,
    ): PreparedInput = withContext(Dispatchers.IO) {
        val info = active.value?.info
        // Decode to PNG bytes immediately — the agent core + litertlm Content.ImageBytes
        // both work in bytes (no android.Bitmap leaks past this layer).
        val images = attachments.filterIsInstance<Attachment.Image>()
            .mapNotNull { decodeBitmap(it.uri)?.toPngBytes() }
            .toMutableList()
        // STICKY documents: use the whole chat's attached docs (not just this message's), so a
        // follow-up like "реши задание 1" still gets the doc. Extract (cached) + index for RAG.
        val documents = chatDocs.map { doc -> doc to cachedOrExtractDocumentText(doc) }
        documents.forEach { (doc, content) ->
            // Index once per retriever lifetime — re-indexing would wipe this doc's vectors
            // (removeDoc) on every send. Chunk ids are deterministic, so a single index suffices.
            if (!content.isNullOrBlank() && retriever.chunkEntries(retrieverDocId(doc)).isEmpty()) {
                retriever.indexDocument(docId = retrieverDocId(doc), docTitle = doc.name, text = content)
            }
        }
        if (effectiveVision(info)) {
            // Vision model: re-send the chat's PDF pages EVERY turn (sticky vision, cached render)
            // so the model keeps SEEING the document — text extraction garbles many PDFs, the page
            // image doesn't. Capped total so a long doc can't flood the context. A pure-text PDF
            // with no figures yields no pages. Text-only bundles never reach here (effectiveVision).
            var pagesAdded = 0
            for ((doc, _) in documents) {
                if (pagesAdded >= STICKY_VISION_PAGE_CAP) break
                if (!isPdf(doc)) continue
                val pages = pdfVisionPages(doc).take(STICKY_VISION_PAGE_CAP - pagesAdded)
                images += pages
                pagesAdded += pages.size
            }
        }
        // Document TEXT is inlined every turn by buildKnowledgeInventory (sticky across the chat,
        // not just this turn) so follow-ups like "реши задание 1" still see the content. Here we
        // only return the images (vision pages) for this turn.
        PreparedInput(images = images, ragContext = "")
    }

    /**
     * Char budget for inlining attached-document text into the initial context.
     * Gives attached files ~55% of the model's input window (split across docs),
     * bounded so a huge PDF can't crowd out the prompt/answer. The rest of long
     * documents stays reachable via the search_files tool.
     */
    private fun attachedDocCharBudget(
        info: com.localchat.domain.model.ModelInfo?,
        docCount: Int,
    ): Int {
        val inputBudget = if (info != null) ContextWindow.inputBudget(info.contextSize, info.maxTokens) else 2048
        val totalChars = (inputBudget * 4 * 0.55).toInt().coerceIn(2_000, 24_000)
        return (totalChars / docCount.coerceAtLeast(1)).coerceAtLeast(1_500)
    }

    private fun decodeBitmap(uri: String): Bitmap? = runCatching {
        val bytes = readBytes(uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxSide = 1280)
        val raw = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                // ARGB_8888 — обязателен для litertlm vision-backend.
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            },
        ) ?: return null
        if (raw.config == Bitmap.Config.ARGB_8888) raw
        else raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
    }.getOrNull()

    /** PNG-encode an ARGB_8888 bitmap for the model (litertlm vision wants ARGB_8888 PNG). */
    private fun Bitmap.toPngBytes(): ByteArray {
        val safe = if (config != Bitmap.Config.ARGB_8888) {
            copy(Bitmap.Config.ARGB_8888, false) ?: return ByteArray(0)
        } else this
        val out = ByteArrayOutputStream()
        return try {
            safe.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } catch (_: Throwable) {
            ByteArray(0)
        } finally {
            if (safe !== this) safe.recycle()
        }
    }

    private fun isPdf(doc: Attachment.Document): Boolean =
        doc.name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

    /**
     * Pages of [doc] to show a vision model: scanned (no text layer) pages AND text pages that
     * carry a real figure/photo/chart. PdfIngestor picks them (capped); a pure-text PDF → none.
     */
    // Rendered PDF page PNGs cached by doc.id — pages are re-sent EVERY turn (sticky vision) so the
    // model keeps seeing the document on follow-ups, but we only rasterize once per doc.
    private val pageRenderCache = java.util.concurrent.ConcurrentHashMap<String, List<ByteArray>>()

    private fun pdfVisionPages(doc: Attachment.Document): List<ByteArray> {
        pageRenderCache[doc.id]?.let { return it }
        val result = runCatching {
            val bytes = readBytes(doc.uri, limitBytes = MAX_DOCUMENT_BYTES)
                ?: return@runCatching emptyList<ByteArray>()
            // Analyse with PDFBox (per-page text + figure detection), but RASTERIZE with the framework
            // renderer (NativePdfRenderer) — PdfBox-Android's renderer yields blank pages.
            val indices = PdfBoxPdfDocument.from(context, bytes).use { pdf ->
                PdfIngestor.plan(
                    pdf,
                    PdfIngestOptions(
                        visionAvailable = true,
                        renderAllUpTo = 5,     // short docs: model reads every page
                        maxRenderPages = 6,    // long docs: cap figure/scanned pages
                        renderVisualPages = true,
                    ),
                ).pagesToRender
            }
            NativePdfRenderer(context).renderPages(bytes, indices).map { bmp ->
                bmp.toPngBytes().also { bmp.recycle() }
            }
        }.getOrDefault(emptyList())
        pageRenderCache[doc.id] = result
        return result
    }

    private fun readDocumentText(doc: Attachment.Document): String? = runCatching {
        val bytes = readBytes(doc.uri, limitBytes = MAX_DOCUMENT_BYTES) ?: return@runCatching null
        DocumentTextExtractor.extract(context, doc.name, bytes)
    }.getOrNull()

    private fun readBytes(uri: String, limitBytes: Int = Int.MAX_VALUE): ByteArray? {
        val stream = when {
            uri.startsWith("content://") || uri.startsWith("file://") ->
                context.contentResolver.openInputStream(Uri.parse(uri))
            else -> File(uri).takeIf { it.exists() }?.inputStream()
        } ?: return null
        return stream.use { input ->
            if (limitBytes == Int.MAX_VALUE) {
                input.readBytes()
            } else {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var remaining = limitBytes
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
                out.toByteArray()
            }
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w > maxSide || h > maxSide) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }
}

private fun List<Message>.toRuntimeHistory(): List<RuntimeChatMessage> =
    filterNot { it.isStreaming }
        .mapNotNull { message ->
            val text = message.text.trim()
            if (text.isEmpty()) {
                null
            } else {
                RuntimeChatMessage(
                    role = when (message.role) {
                        Role.User -> RuntimeChatRole.User
                        Role.Assistant -> RuntimeChatRole.Model
                    },
                    text = text,
                )
            }
        }

private data class PreparedInput(
    val images: List<ByteArray>,
    val ragContext: String = "",
)

private const val MAX_DOCUMENT_BYTES = 32 * 1024 * 1024
private const val DEFAULT_CONTEXT_TOKENS = 4096

/** Max PDF page images re-sent to a vision model per turn (sticky vision), bounding context. */
private const val STICKY_VISION_PAGE_CAP = 5

/** Full context-window usage for the top meter. */
data class ContextUsage(
    val usedTokens: Int,
    val totalTokens: Int,
    val progress: Float,
)

data class ComposerState(
    val input: String = "",
    val attachments: List<Attachment> = emptyList(),
    /** Kept for compatibility with the base UI; this offline build has no network tools. */
    val webSearch: Boolean = false,
    val thinking: Boolean = false,
)










