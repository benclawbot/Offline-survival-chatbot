package com.localchat.runtime

import android.content.Context
import android.util.Log
import com.localchat.domain.model.Accelerator
import com.localchat.domain.model.ModelInfo
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiteRtRuntimeImpl"

@Singleton
class LiteRtRuntimeImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LiteRtRuntime {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loadedModel: ModelInfo? = null
    private var loadedSystemPrompt: String = ""
    private var loadedSupportImage: Boolean = false
    private var loadedFamily: TemplateFamily = TemplateFamily.UNKNOWN

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Idle)
    override val state = _state.asStateFlow()

    private val _capabilities = MutableStateFlow<RuntimeCapabilities?>(null)
    override val capabilities = _capabilities.asStateFlow()

    @OptIn(ExperimentalApi::class)
    override suspend fun load(
        model: ModelInfo,
        modelPath: String,
        supportImage: Boolean,
        systemPrompt: String,
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
            unloadInternal()
            _state.value = RuntimeState.Loading(model.id)

            val backend = model.toBackend()
            Log.d(TAG, "load(): model=${model.id} accelerator=${model.accelerator} path=$modelPath supportImage=$supportImage")

            val cfg = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                // Vision encoder runs on GPU, audio on CPU — independent of the text backend.
                // Verified against google-ai-edge/gallery (LlmChatModelHelper): visionBackend
                // "must be GPU for Gemma", audioBackend "must be CPU". The encoders ship INSIDE
                // the single .litertlm file (engine logs TF_LITE_VISION_ENCODER on load); there
                // is no separate vision download. Leaving visionBackend null = encoder never
                // loads = images always fail, which is what bit us before.
                visionBackend = if (supportImage && model.supportsVision) Backend.GPU() else null,
                audioBackend = if (model.supportsAudio) Backend.CPU() else null,
                maxNumTokens = model.contextSize.coerceAtLeast(model.maxTokens),
                cacheDir = if (modelPath.startsWith("/data/local/tmp"))
                    context.getExternalFilesDir(null)?.absolutePath else null,
            )

            // gallery так же делает: проверяет capabilities ДО Engine() и оборачивает init в флаги.
            var supportsSpeculative = false
            try {
                Capabilities(modelPath).use {
                    supportsSpeculative = it.hasSpeculativeDecodingSupport()
                }
            } catch (_: Throwable) { /* не блокер */ }
            // Auto-enable whenever the model bundle ships a draft/speculative head — a
            // free decode speedup. Capability is detected from the file itself, so this
            // is safe per-model (models without the head simply keep it off).
            val useSpeculative = supportsSpeculative
            Log.d(TAG, "load(): speculativeSupported=$supportsSpeculative → useSpeculative=$useSpeculative")

            val visionConfigured = supportImage && model.supportsVision
            try {
                ExperimentalFlags.enableSpeculativeDecoding = useSpeculative
                val e = Engine(cfg).also { it.initialize() }
                ExperimentalFlags.enableSpeculativeDecoding = false

                // Probe the ACTUAL bundle for its chat-template family (drives the tool dialect
                // and thinking-channel markers) by rendering with the engine's own template. We
                // do NOT probe vision by encoding: "Failed to decode image" is an image-decode
                // limitation (and fails on desktop JVM even when the encoder is present), not a
                // capability signal. Vision is taken from the curated flag, same as gallery.
                val family = probeTemplateFamily(e)
                val visionEnabled = visionConfigured

                val c = e.createConfiguredConversation(
                    model = model,
                    systemPrompt = systemPrompt,
                    backend = backend,
                    initialMessages = emptyList(),
                    family = family,
                )
                engine = e
                conversation = c
                loadedModel = model
                loadedSystemPrompt = systemPrompt
                loadedSupportImage = supportImage
                loadedFamily = family
                _capabilities.value = RuntimeCapabilities(
                    modelId = model.id,
                    visionEnabled = visionEnabled,
                    templateFamily = family,
                    supportsSpeculative = supportsSpeculative,
                )
                _state.value = RuntimeState.Loaded(model.id)
                Log.i(TAG, "load(): success model=${model.id} family=$family " +
                    "visionEnabled=$visionEnabled(declared=${model.supportsVision}) spec=$supportsSpeculative")
            } catch (t: Throwable) {
                Log.e(TAG, "load(): FAILED model=${model.id} on ${model.accelerator}: ${t.message}", t)
                _state.value = RuntimeState.Error(t.message ?: "Failed to load model")
                throw t
            }
        }
        }
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock { unloadInternal() }
    }

    private fun unloadInternal() {
        try { conversation?.close() } catch (t: Throwable) { Log.w(TAG, "conversation close", t) }
        try { engine?.close() } catch (t: Throwable) { Log.w(TAG, "engine close", t) }
        conversation = null
        engine = null
        loadedModel = null
        loadedSystemPrompt = ""
        loadedSupportImage = false
        loadedFamily = TemplateFamily.UNKNOWN
        _capabilities.value = null
        _state.value = RuntimeState.Idle
    }

    @OptIn(ExperimentalApi::class)
    override suspend fun resetConversation(
        systemPrompt: String?,
        initialMessages: List<RuntimeChatMessage>,
        model: ModelInfo?,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val e = engine ?: return@withLock
            val m = model ?: loadedModel ?: return@withLock
            val prompt = systemPrompt ?: loadedSystemPrompt
            try { conversation?.close() } catch (_: Throwable) {}
            conversation = e.createConfiguredConversation(
                model = m,
                systemPrompt = prompt,
                backend = m.toBackend(),
                initialMessages = initialMessages,
                family = loadedFamily,
            )
            loadedSystemPrompt = prompt
            if (model != null) loadedModel = m
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun Engine.createConfiguredConversation(
        model: ModelInfo,
        systemPrompt: String,
        backend: Backend,
        initialMessages: List<RuntimeChatMessage>,
        family: TemplateFamily,
    ): Conversation {
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        return createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = model.topK,
                    topP = model.topP.toDouble(),
                    temperature = model.temperature.toDouble(),
                ),
                systemInstruction = systemPrompt.toSystemInstruction(),
                tools = emptyList(),
                initialMessages = initialMessages.toLiteRtMessages(),
                // Split the model's reasoning into channels["thought"] so it lands in the
                // collapsible "Thought for" block and never leaks into the visible answer.
                // Markers differ by family (verified on-device + harness render probe):
                //   ChatML (Qwen3): <think> … </think>
                //   Gemma 4:        <|channel>thought … <channel|>
                channels = thoughtChannelsFor(family),
            )
        )
    }

    /** Reasoning-channel markers, chosen by the family the engine actually rendered. */
    private fun thoughtChannelsFor(family: TemplateFamily): List<Channel> =
        if (family == TemplateFamily.GEMMA) {
            listOf(Channel("thought", "<|channel>thought", "<channel|>"))
        } else {
            listOf(Channel("thought", "<think>", "</think>"))
        }

    /**
     * Classify the bundle's chat-template family from the string the engine actually renders for
     * a probe message (Hermes/ChatML `<|im_start|>` vs Gemma `<|turn>`/`<start_of_turn>`). Runs
     * on a throwaway conversation — only one conversation may exist per engine, so it is closed
     * before the real one is created. Best-effort: any failure degrades to UNKNOWN.
     */
    @OptIn(ExperimentalApi::class)
    private fun probeTemplateFamily(engine: Engine): TemplateFamily {
        return try {
            engine.createConversation(
                ConversationConfig(systemInstruction = Contents.of("You are a helpful assistant."))
            ).use { probe ->
                ModelTemplateProbe.classifyFamily(
                    probe.renderMessageIntoString(LiteRtMessage.user("Hello"), emptyMap<String, Any>())
                )
            }.also { Log.d(TAG, "probeTemplateFamily: $it") }
        } catch (t: Throwable) {
            Log.w(TAG, "probeTemplateFamily failed (non-blocking): ${t.message}")
            TemplateFamily.UNKNOWN
        }
    }

    private fun ModelInfo.toBackend(): Backend = when (accelerator) {
        Accelerator.CPU -> Backend.CPU()
        Accelerator.GPU -> Backend.GPU()
    }

    private fun String.toSystemInstruction(): Contents? =
        trim().takeIf { it.isNotEmpty() }?.let { Contents.of(it) }

    override fun stream(
        prompt: String,
        images: List<ByteArray>,
        extraContext: Map<String, String>,
    ): Flow<InferenceChunk> = callbackFlow {
        val conv = conversation
        if (conv == null) {
            trySend(InferenceChunk.Error("Model is not loaded"))
            close()
            return@callbackFlow
        }

        val started = System.currentTimeMillis()
        var firstTokenAt = 0L
        var tokens = 0

        val parts = mutableListOf<Content>()
        // Image parts go BEFORE text (gallery does the same). Bytes are already
        // PNG/ARGB_8888-encoded by the caller (ChatViewModel.toPngBytes).
        for (bytes in images) {
            if (bytes.isNotEmpty()) parts.add(Content.ImageBytes(bytes))
        }
        if (prompt.isNotBlank()) parts.add(Content.Text(prompt))

        if (parts.isEmpty()) {
            trySend(InferenceChunk.Error("Empty prompt"))
            close()
            return@callbackFlow
        }

        val callback = object : MessageCallback {
            override fun onMessage(message: LiteRtMessage) {
                if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                tokens++
                trySend(
                    InferenceChunk.Token(
                        text = message.toString(),
                        thinking = message.channels["thought"],
                    )
                )
            }

            override fun onDone() {
                val now = System.currentTimeMillis()
                trySend(
                    InferenceChunk.Done(
                        InferenceMetrics(
                            firstTokenLatencyMs = if (firstTokenAt > 0) firstTokenAt - started else 0,
                            totalTimeMs = now - started,
                            tokenCount = tokens,
                        )
                    )
                )
                close()
            }

            override fun onError(throwable: Throwable) {
                if (throwable is CancellationException) {
                    val now = System.currentTimeMillis()
                    trySend(
                        InferenceChunk.Done(
                            InferenceMetrics(
                                firstTokenLatencyMs = if (firstTokenAt > 0) firstTokenAt - started else 0,
                                totalTimeMs = now - started,
                                tokenCount = tokens,
                            )
                        )
                    )
                } else {
                    Log.e(TAG, "inference error", throwable)
                    trySend(InferenceChunk.Error(throwable.message ?: "Inference error"))
                }
                close()
            }
        }

        try {
            conv.sendMessageAsync(Contents.of(parts), callback, extraContext)
        } catch (t: Throwable) {
            Log.e(TAG, "sendMessageAsync threw", t)
            trySend(InferenceChunk.Error(t.message ?: "Inference dispatch failed"))
            close()
            return@callbackFlow
        }

        awaitClose {
            try { conv.cancelProcess() } catch (_: Throwable) {}
        }
    }

    override fun stop() {
        try { conversation?.cancelProcess() } catch (t: Throwable) { Log.w(TAG, "stop", t) }
    }

    private fun List<RuntimeChatMessage>.toLiteRtMessages(): List<LiteRtMessage> =
        mapNotNull { message ->
            val text = message.text.trim()
            if (text.isEmpty()) {
                null
            } else {
                when (message.role) {
                    RuntimeChatRole.User -> LiteRtMessage.user(text)
                    RuntimeChatRole.Model -> LiteRtMessage.model(text)
                }
            }
        }
}

