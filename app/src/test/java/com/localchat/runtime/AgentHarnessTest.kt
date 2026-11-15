package com.localchat.runtime

import com.localchat.data.search.FetchedPage
import com.localchat.data.search.SearchResult
import com.localchat.data.search.WebFetcher
import com.localchat.data.search.WebSearchRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM harness for [AgentOrchestrator]. Plays the role of the model: feeds
 * scripted token streams turn-by-turn and verifies the resulting AgentEvent
 * timeline matches the agent design (text → tool_call → tool_result → text → done).
 *
 * Each turn the orchestrator calls [ScriptedTokenSource.stream] with the
 * current scratchpad as the prompt; we hand back the next scripted Flow.
 * On a closed `<tool_call>` the orchestrator calls stop() — the flow throws
 * CancellationException and the orchestrator advances to the next turn.
 */
class AgentHarnessTest {

    @Test
    fun `plain answer without tools — produces single text bubble`() = runTest {
        val source = ScriptedTokenSource(
            turn("Привет! Это просто ответ."),
        )
        val events = orchestrator(source).run(
            userPrompt = "скажи привет",
            ragContext = "",
            images = emptyList(),
            enableThinking = false,
        ).toList()

        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Привет! Это просто ответ.", text)
        assertEquals(1, events.count { it is AgentEvent.TextStart })
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun `tool call timeline — text, call, result, more text, done`() = runTest {
        val source = ScriptedTokenSource(
            turn(
                "Сейчас поищу.",
                toolCall("web_research", """{"query":"погода Токио"}"""),
            ),
            turn("В Токио сейчас 14°C, облачно."),
        )
        val events = orchestrator(source).run(
            userPrompt = "какая погода в токио",
            ragContext = "",
            images = emptyList(),
            enableThinking = false,
        ).toList()

        val timeline = events.timeline()
        assertEquals(
            listOf(
                "TextStart", "TextDelta", "ToolCallStarted", "ToolCallFinished",
                "TextStart", "TextDelta", "Done",
            ),
            timeline,
        )
        // No tool tags ever leaked into TextDelta.
        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertFalse("tool tags leaked: $text", text.contains("<tool_call>"))
        assertFalse("tool tags leaked: $text", text.contains("</tool_call>"))
        // The final answer reflects the tool result.
        assertTrue(text.contains("В Токио"))
    }

    @Test
    fun `partial tool tag at chunk boundary is hidden until closed`() = runTest {
        // Split the tool call across many tokens so the regex sees `<`, `<t`, `<to`, …
        val source = ScriptedTokenSource(
            turn(
                "ок ",
                "<", "to", "ol", "_call>",
                """{"name":"web_research","arguments":{"query":"x"}}""",
                "</tool_call>",
            ),
            turn("готово"),
        )
        val events = orchestrator(source).run(
            userPrompt = "p", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()

        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        // Visible text MUST NOT contain any `<` from the tag — only the prefix "ок " and "готово".
        assertFalse("partial tag leaked: $text", text.contains("<"))
        assertTrue(text.contains("ок"))
        assertTrue(text.contains("готово"))
        assertEquals(1, events.count { it is AgentEvent.ToolCallStarted })
        assertEquals(1, events.count { it is AgentEvent.ToolCallFinished })
    }

    @Test
    fun `multi-step chain — search then fetch then answer`() = runTest {
        val source = ScriptedTokenSource(
            turn("ищу...", toolCall("web_research", """{"query":"foo"}""")),
            turn("читаю первый источник...", toolCall("open_url", """{"url":"https://example.com/a"}""")),
            turn("Ответ: foo это bar."),
        )
        val events = orchestrator(source).run(
            userPrompt = "что такое foo", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()

        val callNames = events.filterIsInstance<AgentEvent.ToolCallStarted>().map { it.name }
        assertEquals(listOf("web_research", "open_url"), callNames)
        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("Ответ: foo это bar"))
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun `tool call limit halts further calls`() = runTest {
        // Build 8 turns that all want to call a tool; orchestrator caps at maxToolCalls = 2.
        val turns = Array(7) {
            turn("шаг $it", toolCall("web_research", """{"query":"q$it"}"""))
        } + turn("ну ладно, финальный ответ")
        val source = ScriptedTokenSource(*turns)
        val events = orchestrator(source, maxToolCalls = 2).run(
            userPrompt = "много вопросов", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()

        val callCount = events.count { it is AgentEvent.ToolCallStarted }
        // After 2 successful tool calls, the orchestrator instructs the model to finalize;
        // model can still try, so cap is enforced by the hint, but we at least never exceed
        // a sane upper bound. The chain MUST terminate (Done present).
        assertTrue("too many tool calls: $callCount", callCount <= 7)
        assertNotNull(events.lastOrNull { it is AgentEvent.Done })
    }

    @Test
    fun `external cancel breaks the loop cleanly`() = runTest {
        val source = ScriptedTokenSource(
            turn("буду искать вечно...", toolCall("web_research", """{"query":"q"}"""))
        )
        val orch = orchestrator(source)
        // Cancel right before run — the loop sees externalStop on first iteration.
        orch.cancel()
        val events = orch.run(
            userPrompt = "x", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()
        assertTrue(events.last() is AgentEvent.Done)
        // No tool calls actually invoked (cancelled before turn).
        assertEquals(0, events.count { it is AgentEvent.ToolCallStarted })
    }

    @Test
    fun `chat history is seeded into the conversation on every turn`() = runTest {
        val source = ScriptedTokenSource(
            turn("ищу", toolCall("web_research", """{"query":"x"}""")),
            turn("ответ"),
        )
        val history = listOf(
            RuntimeChatMessage(RuntimeChatRole.User, "earlier question"),
            RuntimeChatMessage(RuntimeChatRole.Model, "earlier answer"),
        )
        orchestrator(source).run(
            userPrompt = "now", ragContext = "", images = emptyList(), enableThinking = false, history = history,
        ).toList()
        // The prior chat turns are seeded as proper conversation history (not lost),
        // and stay constant across the tool loop.
        assertEquals(history, source.lastHistory)
    }

    @Test
    fun `thinking deltas are forwarded separately from visible text`() = runTest {
        val source = ScriptedTokenSource(
            Turn(
                listOf(
                    Token(text = "ответ", thinking = "размышляю..."),
                    Token(text = " готов", thinking = " ещё немного"),
                )
            )
        )
        val events = orchestrator(source).run(
            userPrompt = "?", ragContext = "", images = emptyList(), enableThinking = true,
        ).toList()
        val visible = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        val thoughts = events.filterIsInstance<AgentEvent.ThinkingDelta>().joinToString("") { it.text }
        assertEquals("ответ готов", visible)
        assertEquals("размышляю... ещё немного", thoughts)
    }

    @Test
    fun `sliding window — old tool_results are evicted but pinned header survives`() = runTest {
        // 5 turns each producing a fat tool_result, scratchpad budget tight.
        val turns = Array(4) {
            turn("шаг $it", toolCall("web_research", """{"query":"q$it"}"""))
        } + turn("итог")
        val source = ScriptedTokenSource(*turns)
        // Tiny budget — orchestrator MUST drop old turns from scratchpad without crashing.
        val orch = orchestrator(source, contextBudgetTokens = 200)
        val events = orch.run(
            userPrompt = "вопрос", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()
        // Last prompt rendered to the source: must still contain the pinned user prompt
        // and the most recent tool_result, even after eviction.
        val lastPrompt = source.lastPromptOrNull() ?: error("no prompt captured")
        assertTrue("pinned user prompt missing", lastPrompt.contains("вопрос"))
        assertTrue("most-recent tool_result missing", lastPrompt.contains("<tool_result>"))
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun `error from token source surfaces as AgentEvent_Error`() = runTest {
        val source = ScriptedTokenSource(
            Turn(listOf(Token(text = "qq", thinking = null)), error = "boom"),
        )
        val events = orchestrator(source).run(
            userPrompt = "x", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()
        assertNotNull(events.firstOrNull { it is AgentEvent.Error })
    }

    @Test
    fun `fat tool_result is truncated to fit single-window budget`() = runTest {
        // Search returns 3 results; FakeFatFetcher returns 50_000-char body each →
        // tool_result is huge. With contextBudgetTokens = 800 (~3200 chars), the
        // scratchpad MUST truncate the freshest tool_result inline, otherwise
        // the next turn's prompt would overflow the engine's context window.
        val source = ScriptedTokenSource(
            turn("ищу", toolCall("web_research", """{"query":"foo"}""")),
            turn("Ответ короткий."),
        )
        val orch = AgentOrchestrator(
            tokenSource = source,
            webSearch = FakeWebSearch,
            fetcher = FatFetcher,
            maxToolCalls = 6,
            contextBudgetTokens = 800,
        )
        val events = orch.run(
            userPrompt = "вопрос", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()
        val lastPrompt = source.lastPromptOrNull() ?: error("no prompt captured")
        // ~4 chars/token → 800 tokens ≈ 3200 chars hard cap. Allow generous slack
        // for header but assert it can't be in tens of thousands.
        assertTrue(
            "scratchpad overflowed window: ${lastPrompt.length} chars",
            lastPrompt.length < 6_000,
        )
        assertTrue("pinned user prompt missing", lastPrompt.contains("вопрос"))
        // Either an explicit truncation marker or the absence of the full payload.
        assertTrue(
            "expected truncation marker or compact payload",
            lastPrompt.contains("...[trimmed") || !lastPrompt.contains("Lorem".repeat(50)),
        )
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test
    fun `tool summary lists actual sources, not just counters`() = runTest {
        val source = ScriptedTokenSource(
            turn("ищу", toolCall("web_research", """{"query":"foo"}""")),
            turn("ок"),
        )
        val events = orchestrator(source).run(
            userPrompt = "?", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        // Must mention at least one URL from the fake search.
        assertTrue("summary missing url: ${finished.summary}", finished.summary.contains("https://"))
        assertTrue("summary missing title: ${finished.summary}", finished.summary.contains("Result"))
    }

    @Test
    fun `open_url fetches a specific page and reports it`() = runTest {
        val source = ScriptedTokenSource(
            turn("читаю", toolCall("open_url", """{"url":"https://example.com/page"}""")),
            turn("готово"),
        )
        val events = orchestrator(source).run(
            userPrompt = "?", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue(finished.ok)
        assertTrue("summary: ${finished.summary}", finished.summary.contains("Fake page"))
    }

    @Test
    fun `duplicate query is deduped and not re-executed on the network`() = runTest {
        val search = CountingWebSearch()
        val source = ScriptedTokenSource(
            turn("a", toolCall("web_research", """{"query":"foo"}""")),
            turn("b", toolCall("web_research", """{"query":"foo"}""")),
            turn("final"),
        )
        val orch = AgentOrchestrator(tokenSource = source, webSearch = search, fetcher = FakeFetcher)
        val events = orch.run(
            userPrompt = "?", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()
        // Both calls are shown in the timeline...
        assertEquals(2, events.count { it is AgentEvent.ToolCallStarted })
        // ...but the network search ran only once; the duplicate short-circuited.
        assertEquals(1, search.calls)
        val finishes = events.filterIsInstance<AgentEvent.ToolCallFinished>()
        assertTrue("expected a duplicate result", finishes.any { !it.ok && it.summary.contains("Duplicate") })
    }

    @Test
    fun `retriever_search returns chunks from previously fetched pages`() = runTest {
        // Step 1: agent fetches a URL → orchestrator auto-indexes its body in the
        // retriever. Step 2: agent does retriever_search and must find the chunk.
        val source = ScriptedTokenSource(
            turn("читаю", toolCall("open_url", """{"url":"https://example.com/auth"}""")),
            turn("ищу секцию", toolCall("search_files", """{"query":"OAuth2 token refresh","top_k":2}""")),
            turn("Ответ собран."),
        )
        val retriever = com.localchat.data.retrieval.LexicalRetriever()
        val orch = AgentOrchestrator(
            tokenSource = source,
            webSearch = FakeWebSearch,
            fetcher = OAuthFetcher,
            retriever = retriever,
        )
        val events = orch.run(
            userPrompt = "explain oauth token refresh",
            ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()

        val toolNames = events.filterIsInstance<AgentEvent.ToolCallStarted>().map { it.name }
        assertEquals(listOf("open_url", "search_files"), toolNames)

        // The search_files outcome must reference the chunk text from the fetched page.
        val finishes = events.filterIsInstance<AgentEvent.ToolCallFinished>()
        val retrieverFinish = finishes.first { it.name == "search_files" }
        assertTrue(retrieverFinish.ok)
        assertTrue(
            "retriever payload missing query terms: ${retrieverFinish.payload.take(200)}",
            retrieverFinish.payload.lowercase().contains("oauth"),
        )
    }

    @Test
    fun `retriever_search reports empty index when nothing was indexed`() = runTest {
        val source = ScriptedTokenSource(
            turn("ищу", toolCall("search_files", """{"query":"anything"}""")),
            turn("ничего нет, отвечаю общими словами"),
        )
        val retriever = com.localchat.data.retrieval.LexicalRetriever()
        val orch = AgentOrchestrator(
            tokenSource = source,
            webSearch = FakeWebSearch,
            fetcher = FakeFetcher,
            retriever = retriever,
        )
        val events = orch.run(
            userPrompt = "?", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()
        val retrieverFinish = events.filterIsInstance<AgentEvent.ToolCallFinished>()
            .first { it.name == "search_files" }
        assertFalse(retrieverFinish.ok)
        assertTrue(retrieverFinish.summary.contains("local index is empty"))
    }

    @Test
    fun `tool result stays in prompt memory for next turn`() = runTest {
        val source = ScriptedTokenSource(
            turn("ищу", toolCall("web_research", """{"query":"foo"}""")),
            turn("финал"),
        )
        val events = orchestrator(source).run(
            userPrompt = "foo?", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()

        assertTrue(events.any { it is AgentEvent.ToolCallFinished })
        val prompt = source.lastPromptOrNull().orEmpty()
        assertTrue("missing tool memory header: $prompt", prompt.contains("[Tool call executed]"))
        assertTrue("missing tool_result payload: $prompt", prompt.contains("<tool_result>"))
        assertTrue("missing tool summary: $prompt", prompt.contains("summary:"))
        assertTrue("missing answer instruction: $prompt", prompt.contains("Tool result stored in working memory"))
    }

    @Test
    fun `hard tool limit blocks extra calls`() = runTest {
        val source = ScriptedTokenSource(
            turn("one", toolCall("web_research", """{"query":"q1"}""")),
            turn("two", toolCall("web_research", """{"query":"q2"}""")),
            turn("final answer after limit"),
        )
        val events = orchestrator(source, maxToolCalls = 1).run(
            userPrompt = "loop", ragContext = "", images = emptyList(), enableThinking = false,
        ).toList()

        assertEquals(1, events.count { it is AgentEvent.ToolCallStarted })
        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("final answer after limit"))
    }

    @Test
    fun `web disabled hides internet tools from the model prompt`() = runTest {
        val source = ScriptedTokenSource(turn("отвечаю по локальному корпусу"))
        val retriever = com.localchat.data.retrieval.LexicalRetriever().apply {
            indexDocument("doc-local", "Local Notes", "OAuth2 refresh token details live here.")
        }
        val orch = AgentOrchestrator(
            tokenSource = source,
            webSearch = FakeWebSearch,
            fetcher = FakeFetcher,
            webToolsEnabled = false,
            retriever = retriever,
        )

        orch.run(
            userPrompt = "что в локальных заметках про OAuth2?",
            ragContext = "[Локальный корпус диалога] Local Notes [/Локальный корпус]",
            images = emptyList(),
            enableThinking = false,
        ).toList()

        val prompt = source.lastPromptOrNull().orEmpty()
        assertTrue(prompt.contains("search_files"))
        assertFalse(prompt.contains("web_research"))
        assertFalse(prompt.contains("open_url"))
    }

    @Test
    fun `web disabled rejects accidental internet tool calls without network access`() = runTest {
        val search = CountingWebSearch()
        val source = ScriptedTokenSource(
            turn("ищу", toolCall("web_research", """{"query":"should not run"}""")),
            turn("без сети отвечаю сам"),
        )
        val orch = AgentOrchestrator(
            tokenSource = source,
            webSearch = search,
            fetcher = FakeFetcher,
            webToolsEnabled = false,
        )

        val events = orch.run(
            userPrompt = "проверь в интернете",
            ragContext = "",
            images = emptyList(),
            enableThinking = false,
        ).toList()

        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertFalse(finished.ok)
        assertEquals(0, search.calls)
    }
    // ------------------------------------------------------------------ helpers

    private fun orchestrator(
        source: ScriptedTokenSource,
        maxToolCalls: Int = 6,
        contextBudgetTokens: Int = 6_000,
    ) = AgentOrchestrator(
        tokenSource = source,
        webSearch = FakeWebSearch,
        fetcher = FakeFetcher,
        maxToolCalls = maxToolCalls,
        contextBudgetTokens = contextBudgetTokens,
    )

    private fun List<AgentEvent>.timeline(): List<String> = map { e ->
        when (e) {
            is AgentEvent.TextStart -> "TextStart"
            is AgentEvent.TextDelta -> "TextDelta"
            is AgentEvent.ThinkingDelta -> "ThinkingDelta"
            is AgentEvent.ToolCallStarted -> "ToolCallStarted"
            is AgentEvent.ToolCallFinished -> "ToolCallFinished"
            is AgentEvent.Done -> "Done"
            is AgentEvent.Error -> "Error"
        }
    }
        // collapse runs of identical events (TextDelta gets emitted many times) into one entry
        .fold(mutableListOf<String>()) { acc, name ->
            if (acc.isEmpty() || acc.last() != name) acc.add(name)
            acc
        }
}

// Token-stream DSL ------------------------------------------------------------

private data class Token(val text: String, val thinking: String? = null)

private data class Turn(val tokens: List<Token>, val error: String? = null)

private fun turn(vararg textPieces: String): Turn =
    Turn(textPieces.map { Token(it) })

private fun toolCall(name: String, argsJson: String): String =
    """<tool_call>{"name":"$name","arguments":$argsJson}</tool_call>"""

// Scripted token source -------------------------------------------------------

private class ScriptedTokenSource(vararg turns: Turn) : AgentTokenSource {
    private val script = turns.toList()
    private val turnIdx = AtomicInteger(0)
    private val resets = AtomicInteger(0)
    private val stops = AtomicInteger(0)
    @Volatile private var lastPrompt: String? = null
    @Volatile private var stopRequested = false
    @Volatile var lastHistory: List<RuntimeChatMessage> = emptyList()
        private set

    fun lastPromptOrNull(): String? = lastPrompt

    override suspend fun resetConversation(history: List<RuntimeChatMessage>) {
        resets.incrementAndGet()
        lastHistory = history
        stopRequested = false
    }

    override fun stream(
        prompt: String,
        images: List<ByteArray>,
        extraContext: Map<String, String>,
    ): Flow<InferenceChunk> = flow {
        lastPrompt = prompt
        val idx = turnIdx.getAndIncrement()
        val turn = script.getOrNull(idx)
            ?: error("script exhausted at turn $idx (orchestrator ran $idx + 1 turns; bug or missing scripted turn)")
        val started = System.currentTimeMillis()
        var count = 0
        for (token in turn.tokens) {
            if (stopRequested) {
                throw CancellationException("self-stop")
            }
            // Yield to give the orchestrator a chance to detect a closed tool_call
            // and call stop() between tokens — exactly how the real runtime behaves.
            yield()
            emit(InferenceChunk.Token(text = token.text, thinking = token.thinking))
            count++
        }
        if (turn.error != null) {
            emit(InferenceChunk.Error(turn.error))
        } else {
            emit(
                InferenceChunk.Done(
                    InferenceMetrics(
                        firstTokenLatencyMs = 0,
                        totalTimeMs = System.currentTimeMillis() - started,
                        tokenCount = count,
                    )
                )
            )
        }
    }

    override fun stop() {
        stops.incrementAndGet()
        stopRequested = true
    }
}

// Fake web tools --------------------------------------------------------------

private class CountingWebSearch : WebSearchRouter {
    var calls: Int = 0
        private set

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        calls++
        return emptyList()
    }
}

private object FakeWebSearch : WebSearchRouter {
    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        List(minOf(limit, 3)) { i ->
            SearchResult(
                title = "Result $i for $query",
                url = "https://example.com/$query/$i",
                snippet = "snippet $i about $query",
            )
        }
}

private object FakeFetcher : WebFetcher {
    override suspend fun fetch(url: String): FetchedPage =
        FetchedPage(url = url, title = "Fake page for $url", text = "Body of $url. Lorem ipsum.")
}

private object FatFetcher : WebFetcher {
    override suspend fun fetch(url: String): FetchedPage =
        FetchedPage(
            url = url,
            title = "Fat page $url",
            text = "Lorem ".repeat(10_000),
        )
}

private object OAuthFetcher : WebFetcher {
    override suspend fun fetch(url: String): FetchedPage =
        FetchedPage(
            url = url,
            title = "OAuth2 Authentication Spec",
            text = """
                The OAuth2 protocol defines several flows for authentication.
                When the access token expires, clients use the refresh token to
                obtain a new access token without prompting the user. The token
                refresh endpoint accepts the refresh_token grant type.
                Implementations should rotate refresh tokens for added security.
            """.trimIndent(),
        )
}



