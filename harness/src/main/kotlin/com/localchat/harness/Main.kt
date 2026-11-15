package com.localchat.harness

import com.localchat.data.retrieval.LexicalRetriever
import com.localchat.data.search.DuckDuckGoSearch
import com.localchat.data.search.HttpWebFetcher
import com.localchat.data.search.UrlHttpClient
import com.localchat.data.search.WebFetcher
import com.localchat.data.search.WebSearchRouter
import com.localchat.runtime.AgentTools2
import com.localchat.runtime.ToolDialect
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * Desktop harness: loads a REAL model via litertlm-jvm and drives the agent loop with
 * the SAME per-family logic as the app — [ToolDialect] (Hermes `<tool_call>` vs Gemma
 * ```tool_code) for the protocol/parse/result, and per-family reasoning channels
 * (Qwen `<think>` vs Gemma `<|channel>thought`) so thinking is split out of the answer.
 *
 * Run: ./gradlew :harness:run --args="<modelPath> [prompt]"   (JDK 21)
 */
private val http = UrlHttpClient()
private val search: WebSearchRouter = DuckDuckGoSearch(http)
private val fetcher: WebFetcher = HttpWebFetcher(http)
private val retriever = LexicalRetriever()

fun main(args: Array<String>) {
    val modelPath = args.getOrNull(0) ?: run {
        println("Usage: ./gradlew :harness:run --args=\"<path-to-model.litertlm|.task> [prompt|--probe]\"")
        return
    }
    val userPrompt = args.getOrNull(1)
    val modelName = File(modelPath).name
    val dialect = ToolDialect.forModel(modelPath, modelName)
    println("=== LiteRT-LM agent harness ===\nmodel: $modelPath\ndialect: ${dialect::class.simpleName}")

    retriever.indexDocument(
        docId = "file:notes.txt", docTitle = "notes.txt",
        text = "Project LocalChat is an offline LLM chat app for Android. The agent has exactly three tools: " +
            "web_research, open_url and search_files. The secret project code name is BLUE-FALCON-42.",
    )

    if (userPrompt == "--probe") { probeCapabilities(modelPath, modelName); return }

    val engine = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), maxNumTokens = 4096))
    engine.initialize()
    println("engine initialized ✓")

    if (userPrompt != null) {
        println("\n──────────────────────────────\nUSER: $userPrompt")
        runAgent(engine, dialect, modelName, userPrompt)
    } else {
        for (p in listOf(
            "What is the square root of one million? Answer briefly.",      // no tools — pure think+answer
            "What is the secret project code name? Use search_files to find it in notes.txt.",  // search_files
        )) {
            println("\n──────────────────────────────\nUSER: $p")
            runAgent(engine, dialect, modelName, p)
        }
        // The real failure the user hit: an attached document whose TEXT is inlined into the
        // context (no images). The model must read that text and solve the task — not ask the
        // user to "provide the content". This mirrors ChatViewModel's ragBlock exactly.
        println("\n──────────────────────────────\nUSER: [attached ${WORKSHEET_TITLE}] реши 1 задание")
        runAgentWithDoc(engine, dialect, modelName, WORKSHEET_TITLE, WORKSHEET_TEXT, "Реши задание: сопоставь слова с описаниями 1–8.")
    }
    engine.close()
    println("\n=== done ===")
}

/** A worksheet like the user's "Information technologies.pdf" — text layer present, no images. */
private const val WORKSHEET_TITLE = "Information technologies.pdf"
private val WORKSHEET_TEXT = """
    Task 1. Read the descriptions 1-8. Match the words in the box to the descriptions.
    Words: adware, Trojan, hacker, virus, browser hijacker, worm, malware, attack, spyware.

    1. Software that automatically displays or downloads advertising material when a user is online.
    2. A program that looks legitimate but performs malicious actions once it is run.
    3. A person who uses computers to gain unauthorized access to data.
    4. A piece of code that copies itself into other files and corrupts the system or destroys data.
    5. Software that changes your browser's homepage or search engine without your permission.
    6. A standalone program that replicates itself in order to spread to other computers.
    7. A general term for any software designed to damage or disrupt a computer system.
    8. Software that secretly gathers information about a user through their internet connection.
""".trimIndent()

/** Drive the loop with the document's text pinned into the first turn — the app's text-first path. */
private fun runAgentWithDoc(
    engine: Engine, dialect: ToolDialect, modelName: String, docTitle: String, docText: String, task: String,
) {
    val inlined = buildString {
        append("Содержимое прикреплённых файлов (используй для ответа; за остальными частями длинных файлов — инструмент search_files):\n")
        append("=== ").append(docTitle).append(" ===\n")
        append(docText).append("\n\n")
        append("Запрос пользователя:\n").append(task)
    }
    runAgent(engine, dialect, modelName, inlined)
}

private fun runAgent(engine: Engine, dialect: ToolDialect, modelName: String, prompt: String) {
    val tools = AgentTools2.available(webToolsEnabled = true, retrieverEnabled = true)
    engine.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(dialect.protocol(tools)),
            automaticToolCalling = false,
            channels = thoughtChannels(modelName),
        )
    ).use { conv ->
        var message = prompt
        var step = 0
        while (step < 5) {
            val resp = conv.sendMessage(message, emptyMap<String, Any>())
            resp.channels["thought"]?.takeIf { it.isNotBlank() }?.let { println("  THINKING: ${it.trim().take(240)}…") }
            val text = resp.toString()
            val call = dialect.parseCall(text)
            if (call == null) {
                println("  ANSWER: ${dialect.stripMarkup(text).trim().take(400)}")
                return
            }
            println("  TOOL_CALL: ${call.name}(${call.argumentsJson})")
            val result = runCatching { execTool(call.name, call.argumentsJson, prompt) }
                .getOrElse { """{"error":"${it.message}"}""" }
            println("  TOOL_RESULT: ${result.take(220)}")
            message = dialect.renderResult(call.name, call.argumentsJson, "ok", true, result, "[Use this to answer.]")
            step++
        }
        println("  [hit tool-call cap]")
    }
}

/**
 * Probe what the ACTUAL model reports/renders — no filename heuristics. We want to learn:
 *  1. speculative decoding support (real Capabilities API),
 *  2. the model's native chat-template turn markers (`<start_of_turn>` vs `<|im_start|>` …),
 *  3. whether the template accepts an IMAGE content (→ vision encoder present) or not.
 */
@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
private fun probeCapabilities(modelPath: String, modelName: String) {
    println("\n===== CAPABILITY PROBE: $modelName =====")
    runCatching {
        com.google.ai.edge.litertlm.Capabilities(modelPath).use { caps ->
            println("hasSpeculativeDecodingSupport = ${caps.hasSpeculativeDecodingSupport()}")
        }
    }.onFailure { println("Capabilities error: ${it.message}") }

    // IMPORTANT: configure visionBackend/audioBackend like gallery does, otherwise the engine
    // has no vision executor and ANY image fails regardless of the model's real capability.
    // Gallery: visionBackend must be GPU for Gemma, audioBackend must be CPU. On this CPU-only
    // PC harness we can only try CPU vision — if that fails it may simply mean "needs GPU".
    val engine = Engine(EngineConfig(
        modelPath = modelPath,
        backend = Backend.CPU(),
        visionBackend = Backend.CPU(),
        audioBackend = Backend.CPU(),
        maxNumTokens = 1024,
    ))
    engine.initialize()
    engine.createConversation(ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant."))).use { conv ->
        // (2) Plain text turn — reveals the real template markers.
        val plain = runCatching {
            conv.renderMessageIntoString(com.google.ai.edge.litertlm.Message.user("Hello there"), emptyMap<String, Any>())
        }.getOrElse { "RENDER ERROR: ${it.message}" }
        println("\n--- render(text user msg) ---\n${plain.take(500)}")

        // (3) Same turn but WITH a tiny 1×1 PNG image — does the template insert an image token?
        val pngBytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC"
        )
        val withImg = runCatching {
            conv.renderMessageIntoString(
                com.google.ai.edge.litertlm.Message.of(
                    com.google.ai.edge.litertlm.Content.Text("Describe this image"),
                    com.google.ai.edge.litertlm.Content.ImageBytes(pngBytes),
                ),
                emptyMap<String, Any>(),
            )
        }.getOrElse { "RENDER ERROR: ${it::class.simpleName}: ${it.message}" }
        println("\n--- render(text+image user msg) ---\n${withImg.take(500)}")
        // Heuristic readout: did an image placeholder token appear?
        val imgTok = listOf("<start_of_image>", "image_soft_token", "<image>", "begin_of_image", "<|image", "[IMG]")
            .firstOrNull { withImg.contains(it, ignoreCase = true) }
        println("\n>>> image token in render: ${imgTok ?: "NONE"}  (NOTE: template handles images even without an encoder)")

        // (4) DEFINITIVE vision probe — actually try to ENCODE the image (prefill). On a model
        // with no vision encoder this must fail; on a real vision model it succeeds.
        val encodeResult = runCatching {
            conv.sendMessage(
                com.google.ai.edge.litertlm.Message.of(
                    com.google.ai.edge.litertlm.Content.Text("Reply with the single word OK."),
                    com.google.ai.edge.litertlm.Content.ImageBytes(pngBytes),
                ),
                emptyMap<String, Any>(),
            ).toString().take(80)
        }
        // NOTE: with visionBackend configured the engine loads TF_LITE_VISION_ENCODER (see log).
        // A failure here on the DESKTOP harness is the image-DECODE step (PNG→pixels), which the
        // JVM build can't do — NOT proof the bundle lacks vision. Real vision needs GPU on device.
        println("\n>>> ENCODE image (sendMessage): " +
            if (encodeResult.isSuccess) "SUCCESS → image encoded. reply='${encodeResult.getOrNull()}'"
            else "FAILED (likely desktop image-decode limit, not capability): " +
                "${encodeResult.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message?.take(160)}" }}")
    }
    engine.close()
    println("===== end probe =====")
}

/** Per-family reasoning-channel markers — mirrors LiteRtRuntimeImpl.thoughtChannelsFor. */
private fun thoughtChannels(modelName: String): List<Channel> =
    if (modelName.lowercase().contains("gemma")) {
        listOf(Channel("thought", "<|channel>thought", "<channel|>"))
    } else {
        listOf(Channel("thought", "<think>", "</think>"))
    }

private fun execTool(name: String, argsJson: String, fallbackQuery: String): String = when (name) {
    "web_research" -> runBlocking {
        val q = JSONObject(argsJson).optString("query").ifBlank { fallbackQuery }
        val hits = runCatching { search.search(q, 5) }.getOrDefault(emptyList())
        val sources = org.json.JSONArray()
        hits.take(2).forEach { hit ->
            val page = runCatching { fetcher.fetch(hit.url) }.getOrNull()
            sources.put(JSONObject().put("title", hit.title).put("url", hit.url)
                .put("text", (page?.text ?: hit.snippet).take(1200)))
        }
        JSONObject().put("query", q).put("sources", sources).toString()
    }
    "open_url" -> runBlocking {
        val url = JSONObject(argsJson).optString("url")
        val page = runCatching { fetcher.fetch(url) }.getOrNull()
        if (page == null) """{"error":"fetch failed"}"""
        else JSONObject().put("url", page.url).put("title", page.title).put("text", page.text.take(2000)).toString()
    }
    "search_files" -> {
        val q = JSONObject(argsJson).optString("query").ifBlank { fallbackQuery }
        val hits = retriever.query(q, budgetTokens = 1200, topK = 4)
        val arr = org.json.JSONArray()
        hits.forEach { arr.put(JSONObject().put("doc", it.chunk.docTitle).put("text", it.chunk.text.take(1000))) }
        JSONObject().put("query", q).put("hits", arr).toString()
    }
    else -> """{"error":"unknown tool $name"}"""
}
