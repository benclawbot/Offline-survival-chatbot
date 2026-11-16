package com.localchat.runtime

import com.localchat.data.retrieval.LexicalRetriever
import com.localchat.data.search.FetchedPage
import com.localchat.data.search.SearchRateLimitedException
import com.localchat.data.search.WebFetcher
import com.localchat.data.search.WebSearchRouter
import com.localchat.domain.chat.ToolPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Поток событий agent-цикла. См. ChatViewModel.runAgentStream. */
sealed interface AgentEvent {
    data object TextStart : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ThinkingDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val name: String, val argumentsJson: String) : AgentEvent
    data class ToolCallFinished(
        val name: String,
        val ok: Boolean,
        val summary: String,
        val payload: String,
    ) : AgentEvent
    data class Done(val metrics: InferenceMetrics) : AgentEvent
    data class Error(val message: String) : AgentEvent
}

/**
 * Минимальный контракт «модели» для AgentOrchestrator. Реальная реализация —
 * адаптер вокруг LiteRtRuntime; для unit-тестов мы подсовываем скрипт.
 *
 * Контракт:
 * - [resetConversation] вызывается перед каждым turn'ом, чтобы у модели не
 *   было хвостов от предыдущего stop()/cancelProcess.
 * - [stream] стримит токены; cancellation потока (или [stop]) обрывает
 *   генерацию. orchestrator различает self-stop (на закрытом tool_call)
 *   и user-stop по флагу [externalStopFlag].
 */
interface AgentTokenSource {
    /**
     * Reset the model conversation before a turn. [history] is the prior chat
     * (already trimmed to the context window) seeded as proper user/model turns,
     * so the always-on agent keeps multi-turn memory while the rolling scratchpad
     * is streamed as the current user message.
     */
    suspend fun resetConversation(history: List<RuntimeChatMessage>)
    fun stream(
        prompt: String,
        images: List<ByteArray>,
        extraContext: Map<String, String>,
    ): Flow<InferenceChunk>
    fun stop()
}

class LiteRtTokenSource(
    private val runtime: LiteRtRuntime,
    private val systemPrompt: String,
) : AgentTokenSource {
    override suspend fun resetConversation(history: List<RuntimeChatMessage>) {
        runCatching {
            runtime.resetConversation(systemPrompt = systemPrompt, initialMessages = history)
        }
    }
    override fun stream(prompt: String, images: List<ByteArray>, extraContext: Map<String, String>) =
        runtime.stream(prompt = prompt, images = images, extraContext = extraContext)
    override fun stop() = runtime.stop()
}

/**
 * Universal agent loop.
 *
 * Каждый turn — новый conversation: в начале turn'а просим [AgentTokenSource]
 * сбросить состояние, чтобы предыдущий stop() на границе tool_call не оставил
 * хвостов в kv-cache, и стримим rolling-scratchpad как один user-message.
 *
 * Скользящее окно (sliding window): rolling-scratchpad строится текстом и
 * каждый turn проверяется на бюджет [contextBudgetTokens]. Когда лимит
 * превышен — режем самый старый блок tool_result, оставляя tools-header и
 * вопрос пользователя нетронутыми. Это удерживает агента в рабочем окне даже
 * после большого smart_research.
 */
class AgentOrchestrator(
    private val tokenSource: AgentTokenSource,
    private val webSearch: WebSearchRouter,
    private val fetcher: WebFetcher,
    private val webToolsEnabled: Boolean = true,
    private val retriever: LexicalRetriever? = null,
    private val survivalLibrary: SurvivalLibrarySearch? = null,
    private val onFetchedPage: (suspend (FetchedPage) -> Unit)? = null,
    private val maxToolCalls: Int = 5,
    /** Максимум токенов scratchpad'а, переживающих переход между turns. */
    private val contextBudgetTokens: Int = 8_000,
    /** Бюджет токенов на ответ retriever_search'а — он живёт в scratchpad'е. */
    private val retrievalBudgetTokens: Int = 2_000,
    /** Tool-call format for the model family (Hermes/Qwen vs Gemma tool_code). */
    private val dialect: ToolDialect = ToolDialect.Hermes,
    /** Embeds a search query for semantic retrieval; null → lexical-only search_files. */
    private val embedQuery: (suspend (String) -> FloatArray?)? = null,
) {

    /** Удобный конструктор для production: оборачивает реальный LiteRtRuntime. */
    constructor(
        runtime: LiteRtRuntime,
        webSearch: WebSearchRouter,
        fetcher: WebFetcher,
        systemPrompt: String,
        webToolsEnabled: Boolean = true,
        retriever: LexicalRetriever? = null,
        survivalLibrary: SurvivalLibrarySearch? = null,
        onFetchedPage: (suspend (FetchedPage) -> Unit)? = null,
        maxToolCalls: Int = 5,
        contextBudgetTokens: Int = 8_000,
        retrievalBudgetTokens: Int = 2_000,
        dialect: ToolDialect = ToolDialect.Hermes,
        embedQuery: (suspend (String) -> FloatArray?)? = null,
    ) : this(
        tokenSource = LiteRtTokenSource(runtime, systemPrompt),
        webSearch = webSearch,
        fetcher = fetcher,
        webToolsEnabled = webToolsEnabled,
        retriever = retriever,
        survivalLibrary = survivalLibrary,
        onFetchedPage = onFetchedPage,
        maxToolCalls = maxToolCalls,
        contextBudgetTokens = contextBudgetTokens,
        retrievalBudgetTokens = retrievalBudgetTokens,
        dialect = dialect,
        embedQuery = embedQuery,
    )

    /** Вызывается из ChatViewModel.stop(); отделяет user-cancel от self-stop на tool_call. */
    private val externalStop = AtomicBoolean(false)
    fun cancel() {
        externalStop.set(true)
        runCatching { tokenSource.stop() }
    }

    /** Current user question — fallback query when a small model emits a tool call with empty args. */
    @Volatile private var fallbackQuery: String = ""

    fun run(
        userPrompt: String,
        ragContext: String,
        images: List<ByteArray>,
        enableThinking: Boolean,
        history: List<RuntimeChatMessage> = emptyList(),
    ): Flow<AgentEvent> = flow {
        fallbackQuery = userPrompt
        val availableTools = AgentTools2.available(
            webToolsEnabled = webToolsEnabled,
            retrieverEnabled = retriever != null || survivalLibrary != null,
        )
        val toolsHeader = if (availableTools.isEmpty()) "" else dialect.protocol(availableTools)
        val scratchpad = Scratchpad(
            header = toolsHeader,
            ragContext = ragContext,
            userPrompt = userPrompt,
            budgetTokens = contextBudgetTokens,
        )

        var toolCalls = 0
        var totalTokens = 0
        var firstTokenAt = 0L
        val startedAt = System.currentTimeMillis()
        // Memory of executed tool queries this turn — prevents the agent from
        // looping on an identical search/fetch (agentic-RAG dedup best practice).
        val executedKeys = HashSet<String>()
        emit(AgentEvent.TextStart)

        loop@ while (true) {
            if (externalStop.get()) break@loop

            tokenSource.resetConversation(history)

            val turnText = StringBuilder()
            var pendingCall: ParsedToolCall? = null
            var streamError: String? = null
            var lastEmittedLen = 0
            var selfStopped = false

            try {
                tokenSource.stream(
                    prompt = scratchpad.render(),
                    images = if (toolCalls == 0) images else emptyList(),
                    extraContext = mapOf("enable_thinking" to enableThinking.toString()),
                ).collect { chunk ->
                    when (chunk) {
                        is InferenceChunk.Token -> {
                            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                            totalTokens++
                            chunk.thinking?.let { emit(AgentEvent.ThinkingDelta(it)) }

                            turnText.append(chunk.text)
                            val visible = dialect.stripMarkup(turnText.toString())
                            if (visible.length > lastEmittedLen) {
                                emit(AgentEvent.TextDelta(visible.substring(lastEmittedLen)))
                                lastEmittedLen = visible.length
                            }
                            (if (availableTools.isNotEmpty()) dialect.parseCall(turnText.toString()) else null)?.let { call ->
                                pendingCall = call
                                selfStopped = true
                                runCatching { tokenSource.stop() }
                            }
                        }
                        is InferenceChunk.Done -> {
                            val visible = dialect.stripMarkup(turnText.toString())
                            if (visible.length > lastEmittedLen) {
                                emit(AgentEvent.TextDelta(visible.substring(lastEmittedLen)))
                                lastEmittedLen = visible.length
                            }
                        }
                        is InferenceChunk.Error -> {
                            streamError = chunk.message
                        }
                    }
                }
            } catch (_: CancellationException) {
                if (!selfStopped) {
                    externalStop.set(true)
                    break@loop
                }
            }

            if (externalStop.get()) break@loop
            streamError?.let { error ->
                emit(AgentEvent.Error(error))
                return@flow
            }

            val call = pendingCall
            if (call == null) {
                scratchpad.appendAssistant(turnText.toString().trim())
                break
            }

            if (toolCalls >= maxToolCalls) {
                scratchpad.appendAssistant(turnText.toString().trim())
                scratchpad.appendSystemHint("Tool limit reached. New tool calls are forbidden. Give the final answer using the collected tool_result data.")
                emit(AgentEvent.TextStart)
                continue@loop
            }

            emit(AgentEvent.ToolCallStarted(call.name, call.argumentsJson))
            toolCalls++

            val dedupKey = dedupKey(call)
            val outcome = if (dedupKey != null && !executedKeys.add(dedupKey)) {
                // Identical query already executed this turn — short-circuit, do not hit the network.
                ToolOutcome(
                    ok = false,
                    summary = "Duplicate ${call.name}: this exact query was already executed this turn.",
                    payload = JSONObject().put("error", "duplicate").put("name", call.name).toString(),
                )
            } else {
                runCatching { invokeTool(call) }.getOrElse {
                    if (it is CancellationException) throw it
                    ToolOutcome(
                        ok = false,
                        summary = "Error: ${it.message ?: "failed"}",
                        payload = JSONObject().put("error", it.message ?: "fail").toString(),
                    )
                }
            }
            emit(AgentEvent.ToolCallFinished(call.name, outcome.ok, outcome.summary, outcome.payload))

            scratchpad.appendAssistant(turnText.toString().trim())
            scratchpad.appendBlock(
                dialect.renderResult(
                    toolName = call.name,
                    argumentsJson = call.argumentsJson,
                    summary = outcome.summary,
                    ok = outcome.ok,
                    payload = outcome.payload,
                    hint = if (toolCalls >= maxToolCalls)
                        "[Tool limit reached. Do not call tools. Give the final answer using the data above.]"
                    else
                        "[Tool result stored in working memory. Use it first; call another tool only if a specific fact is still missing.]",
                )
            )
            emit(AgentEvent.TextStart)
        }

        emit(
            AgentEvent.Done(
                InferenceMetrics(
                    firstTokenLatencyMs = if (firstTokenAt > 0) firstTokenAt - startedAt else 0,
                    totalTimeMs = System.currentTimeMillis() - startedAt,
                    tokenCount = totalTokens,
                )
            )
        )
    }.flowOn(Dispatchers.Default)

    // ---- helpers ---------------------------------------------------------

    private data class ToolOutcome(val ok: Boolean, val summary: String, val payload: String)

    /** Stable per-turn key used to detect repeated tool calls; null = not deduped. */
    private fun dedupKey(call: ParsedToolCall): String? {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return null
        val key = when (call.name) {
            AgentTools2.WEB_RESEARCH.name -> "web_research:" + args.optString("query").trim().lowercase()
            AgentTools2.OPEN_URL.name -> "open_url:" + args.optString("url").trim().lowercase()
            AgentTools2.SEARCH_FILES.name ->
                "search_files:" + args.optString("query").trim().lowercase() +
                    "|" + args.optString("filename").trim().lowercase()
            else -> return null
        }
        return key.takeIf { it.substringAfter(':').isNotBlank() }
    }

    private suspend fun invokeTool(call: ParsedToolCall): ToolOutcome {
        val enabledNames = AgentTools2.available(
            webToolsEnabled = webToolsEnabled,
            retrieverEnabled = retriever != null || survivalLibrary != null,
        ).mapTo(mutableSetOf()) { it.name }
        if (call.name !in enabledNames) {
            return ToolOutcome(
                false,
                "tool is disabled in the current mode",
                JSONObject().put("error", "tool disabled").put("name", call.name).toString(),
            )
        }

        return when (call.name) {
            AgentTools2.WEB_RESEARCH.name -> doWebResearch(call.argumentsJson)
            AgentTools2.OPEN_URL.name -> doOpenUrl(call.argumentsJson)
            AgentTools2.SEARCH_FILES.name -> doSearchFiles(call.argumentsJson)
            else -> ToolOutcome(
                false, "unknown tool ${call.name}",
                JSONObject().put("error", "unknown tool").put("name", call.name).toString(),
            )
        }
    }

    private suspend fun doOpenUrl(argsJson: String): ToolOutcome {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
        val url = args?.optString("url").orEmpty().trim()
        if (url.isBlank()) return ToolOutcome(false, "empty url",
            JSONObject().put("error", "empty url").toString())
        val page = fetcher.fetch(url)
            ?: return ToolOutcome(false, "failed to fetch $url",
                JSONObject().put("error", "fetch failed").put("url", url).toString())
        // Index the page so a later retriever_search can revisit it without
        // refetching. docId = url, so re-fetching same URL just replaces.
        retriever?.indexDocument(docId = page.url, docTitle = page.title.ifBlank { page.url }, text = page.text)
        cacheFetchedPage(page)
        val payload = JSONObject()
            .put("url", page.url).put("title", page.title).put("text", page.text)
        return ToolOutcome(true, "fetched ${page.title.ifBlank { url }}", payload.toString())
    }

    /** Primary fact tool: search → fetch top-K in parallel → hand the model the texts. */
    private suspend fun doWebResearch(argsJson: String): ToolOutcome {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
        // Small models sometimes emit web_research with no query — fall back to the user's question.
        val q = args?.optString("query").orEmpty().trim().ifBlank { fallbackQuery.trim() }
        val topK = args?.optInt("top_k", 3)?.coerceIn(1, 5) ?: 3
        if (q.isBlank()) return ToolOutcome(false, "empty query",
            JSONObject().put("error", "empty query").toString())
        val hits = try {
            webSearch.search(q, limit = 8)
        } catch (e: SearchRateLimitedException) {
            return ToolOutcome(
                false,
                "Search is rate-limited right now. Tell the user to retry shortly, or answer from what you already know.",
                JSONObject().put("error", "rate_limited").put("query", q).toString(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (hits.isEmpty()) {
            return ToolOutcome(false, "search \"$q\": 0 results",
                JSONObject().put("query", q).put("results", JSONArray()).toString())
        }
        val targets = hits.filter { it.url.startsWith("http") }.take(topK)
        val fetched = coroutineScope {
            targets.map { hit ->
                async(Dispatchers.IO) { hit to runCatching { fetcher.fetch(hit.url) }.getOrNull() }
            }.map { it.await() }
        }
        // Auto-index successfully fetched pages so a follow-up retriever_search
        // can dig deeper without re-fetching from the network.
        for ((hit, page) in fetched) {
            if (page != null) {
                retriever?.indexDocument(
                    docId = page.url,
                    docTitle = page.title.ifBlank { hit.title.ifBlank { page.url } },
                    text = page.text,
                )
                cacheFetchedPage(page)
            }
        }
        val payload = JSONObject().apply {
            put("query", q)
            put("sources", JSONArray().apply {
                fetched.forEach { (hit, page) ->
                    put(JSONObject().apply {
                        put("title", hit.title)
                        put("url", hit.url)
                        put("snippet", hit.snippet)
                        if (page != null) {
                            put("text", page.text.take(4_000))
                        }
                    })
                }
            })
            put("more", JSONArray().apply {
                hits.drop(targets.size).forEach {
                    put(JSONObject()
                        .put("title", it.title).put("url", it.url).put("snippet", it.snippet))
                }
            })
        }
        val ok = fetched.any { it.second != null }
        val summary = buildString {
            append("research \"").append(q).append("\": fetched ").append(fetched.count { it.second != null }).append(" of ").append(hits.size)
            fetched.forEachIndexed { i, (hit, page) ->
                val mark = if (page != null) "ok" else "miss"
                append("\n${i + 1}. $mark ${hit.title.ifBlank { hit.url }} - ${hit.url}")
            }
            val rest = hits.size - fetched.size
            if (rest > 0) append("\n...and $rest more source(s)")
        }
        return ToolOutcome(ok, summary, payload.toString())
    }

    /**
     * Локальный лексический поиск по уже проиндексированному корпусу: документы
     * пользователя + страницы, скачанные через fetch_url/smart_research в этом
     * диалоге. Это позволяет агенту вернуться к уже найденному источнику без
     * повторного запроса в сеть, и точечно копать в приложенных файлах.
     */
    private suspend fun doSearchFiles(argsJson: String): ToolOutcome {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
        val q = args?.optString("query").orEmpty().trim().ifBlank { fallbackQuery.trim() }
        val topK = args?.optInt("top_k", 4)?.coerceIn(1, 10) ?: 4
        val filename = args?.optString("filename").orEmpty().takeIf { it.isNotBlank() }
        if (q.isBlank()) return ToolOutcome(false, "empty query",
            JSONObject().put("error", "empty query").toString())
        val libraryHits = runCatching { survivalLibrary?.search(q, topK).orEmpty() }.getOrDefault(emptyList())
        val r = retriever
        if (r == null || r.size == 0) {
            if (libraryHits.isEmpty()) return ToolOutcome(false, "no local source matched \"$q\"",
                JSONObject().put("query", q).put("hits", JSONArray()).toString())
            val payload = JSONObject().apply {
                put("query", q)
                put("hits", JSONArray().apply { libraryHits.forEach { hit ->
                    put(JSONObject().put("doc_title", hit.title).put("source", hit.source)
                        .put("url", hit.url).put("license", hit.license).put("text", hit.text))
                } })
            }
            return ToolOutcome(true, "Survival Essentials: ${libraryHits.size} source passage(s)", payload.toString())
        }

        val docFilter: ((String) -> Boolean)? = filename?.let { needle ->
            val lower = needle.lowercase()
            val predicate: (String) -> Boolean = { docId -> docId.lowercase().contains(lower) }
            predicate
        }
        // Embed the query for semantic ranking (fused with lexical inside query()); null → lexical.
        val queryVector = runCatching { embedQuery?.invoke(q) }.getOrNull()
        val hits = r.query(
            text = q,
            budgetTokens = retrievalBudgetTokens,
            topK = topK,
            docFilter = docFilter,
            queryVector = queryVector,
        )
        if (hits.isEmpty() && libraryHits.isEmpty()) {
            return ToolOutcome(false, "no local hits for \"$q\"${filename?.let { " (in $it)" } ?: ""}",
                JSONObject().put("query", q).put("hits", JSONArray()).toString())
        }
        val payload = JSONObject().apply {
            put("query", q)
            put("hits", JSONArray().apply {
                libraryHits.forEach { hit ->
                    put(JSONObject().put("doc_title", hit.title).put("source", hit.source)
                        .put("url", hit.url).put("license", hit.license).put("text", hit.text))
                }
                hits.forEach { hit ->
                    put(JSONObject().apply {
                        put("doc_id", hit.chunk.docId)
                        put("doc_title", hit.chunk.docTitle)
                        put("ordinal", hit.chunk.ordinal)
                        if (hit.chunk.sectionHeading != null) put("section", hit.chunk.sectionHeading)
                        put("score", hit.score)
                        put("text", hit.chunk.text)
                    })
                }
            })
        }
        val summary = buildString {
            append("local sources for \"").append(q).append("\": ").append(libraryHits.size + hits.size).append(" passage(s)")
            libraryHits.take(3).forEachIndexed { i, hit -> append("\n${i + 1}. ${hit.title} (${hit.source})") }
            hits.take(3).forEachIndexed { i, hit ->
                append("\n${i + 1}. ${hit.chunk.docTitle}")
                hit.chunk.sectionHeading?.let { append(" > $it") }
            }
            if (hits.size > 3) append("\n...and ${hits.size - 3} more")
        }
        return ToolOutcome(true, summary, payload.toString())
    }

    private suspend fun cacheFetchedPage(page: FetchedPage) {
        try {
            onFetchedPage?.invoke(page)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cache failures must not break the answer; the in-memory retriever still has the page.
        }
    }

    /**
     * Скользящий буфер scratchpad'а. Хранит блоки turns; при превышении бюджета
     * выкидывает самый старый assistant/tool_result. Если даже после полной
     * очистки старых turns свежий блок > бюджета (например, толстый
     * smart_research) — режем его текст по символам, чтобы фактически удержать
     * окно. Header+user-prompt всегда нетронуты.
     */
    private class Scratchpad(
        private val header: String,
        ragContext: String,
        userPrompt: String,
        private val budgetTokens: Int,
    ) {
        private val pinned: String = buildString {
            append(header)
            if (ragContext.isNotBlank()) {
                append("\n\n[Local context]\n")
                append(ragContext)
                append("\n[/Local context]")
            }
            append("\n\nUser request:\n")
            append(userPrompt)
        }
        private val turns: ArrayDeque<String> = ArrayDeque()

        fun appendAssistant(text: String) {
            if (text.isBlank()) return
            turns.addLast("\n\nAssistant: $text")
            trimToBudget()
        }

        /** Append a pre-rendered tool-result block (formatted by the active [ToolDialect]). */
        fun appendBlock(block: String) {
            turns.addLast("\n$block")
            trimToBudget()
        }

        fun appendSystemHint(hint: String) {
            turns.addLast("\n\n[System] $hint")
            trimToBudget()
        }

        fun render(): String = buildString {
            append(pinned)
            turns.forEach { append(it) }
        }

        private fun trimToBudget() {
            // Сначала выкидываем старейшие turns, пока что-то можно выкинуть.
            while (turns.size > 1 && estimate() > budgetTokens) {
                turns.removeFirst()
            }
            // Если последний оставшийся turn всё равно слишком большой —
            // режем его текст. Это крайний случай (фат tool_result после
            // 4-5 источников smart_research), но без него engine упадёт на
            // INVALID_ARGUMENT: input is too long.
            if (turns.size == 1 && estimate() > budgetTokens) {
                val pinnedTokens = (pinned.length + 3) / 4
                val budgetForTurn = (budgetTokens - pinnedTokens).coerceAtLeast(256)
                val maxChars = budgetForTurn * 4
                val onlyTurn = turns.first()
                if (onlyTurn.length > maxChars) {
                    val cut = onlyTurn.take(maxChars - TRUNC_MARKER.length).trimEnd() + TRUNC_MARKER
                    turns.clear()
                    turns.addLast(cut)
                }
            }
        }

        private fun estimate(): Int {
            var chars = pinned.length
            turns.forEach { chars += it.length }
            return (chars + 3) / 4
        }

        private companion object {
            const val TRUNC_MARKER = "\n...[trimmed to fit the model context]"
        }
    }
}

fun ToolPart.Result.shortLabel(): String = "${name}: ${summary}".take(160)








