package com.localchat.data.model

import android.content.Context
import android.util.Log
import com.localchat.data.prefs.AppPreferences
import com.localchat.data.prefs.AppSettings
import com.localchat.data.prefs.PersistedModelConfig
import com.localchat.domain.model.Accelerator
import com.localchat.domain.model.EmbeddingFiles
import com.localchat.domain.model.ModelInfo
import com.localchat.domain.model.ModelKind
import com.localchat.domain.model.ModelSource
import com.localchat.domain.model.ModelState
import com.localchat.domain.repo.ModelRepository
import com.localchat.download.DownloadForegroundService
import com.localchat.notifications.DownloadNotifier
import com.localchat.notifications.RuntimeNotifier
import com.localchat.runtime.LiteRtRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ModelRepo"

@Singleton
class FileModelRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val runtime: LiteRtRuntime,
    private val prefs: AppPreferences,
    private val notifier: DownloadNotifier,
    private val runtimeNotifier: RuntimeNotifier,
) : ModelRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = mutableMapOf<String, Job>()
    private val loadMutex = Mutex()

    private val states = MutableStateFlow(initial())
    private val activeId = MutableStateFlow<String?>(null)

    // Latest settings snapshot for sync access (embeddingModelFiles is called off the embed path).
    @Volatile private var currentSettings = AppSettings()

    init {
        scope.launch { prefs.settings.collect { currentSettings = it } }
        scope.launch {
            prefs.modelConfigs.collect { configs ->
                if (configs.isEmpty()) return@collect
                states.value = states.value.map { st ->
                    st.withInfo(applyPersistedConfig(st.info, configs[st.info.id]))
                }
            }
        }
    }

    override fun observeStates(): StateFlow<List<ModelState>> = states.asStateFlow()

    override fun observeActive() = combine(states, activeId) { list, id ->
        if (id == null) null
        else list.firstOrNull { it.info.id == id } as? ModelState.Loaded
    }

    override fun embeddingModelFiles(): EmbeddingFiles? {
        if (!currentSettings.semanticRagEnabled) return null
        val downloaded = states.value
            .filter { it.info.kind == ModelKind.Embedding && (it is ModelState.Downloaded || it is ModelState.Loaded) }
        // Selected model, else the first downloaded embedding model.
        val info = (downloaded.firstOrNull { it.info.id == currentSettings.embeddingModelId }
            ?: downloaded.firstOrNull())?.info ?: return null
        val src = info.source as? ModelSource.HuggingFace ?: return null
        val tokName = src.tokenizerFile?.substringAfterLast('/') ?: return null
        val model = modelFile(info)
        val tokenizer = File(model.parentFile, tokName)
        return if (model.exists() && tokenizer.exists()) EmbeddingFiles(model, tokenizer) else null
    }

    override suspend fun load(modelId: String) {
        loadMutex.withLock {
            val current = states.value
            val target = current.firstOrNull { it.info.id == modelId } ?: return
            if (target is ModelState.Loading) return
            if (target !is ModelState.Downloaded && target !is ModelState.Loaded) {
                throw IllegalStateException("Download the model first")
            }

            val path = when (val src = target.info.source) {
                is ModelSource.LocalFile -> src.absolutePath
                is ModelSource.HuggingFace -> modelFile(target.info).absolutePath
                ModelSource.Builtin -> throw IllegalStateException("No file was specified for the built-in model")
            }
            if (!File(path).exists()) {
                throw IllegalStateException("Model file not found: $path")
            }
            validateModelFile(target.info, File(path))

            states.value = current.map { st ->
                when {
                    st.info.id == modelId -> ModelState.Loading(target.info)
                    st is ModelState.Loaded -> ModelState.Downloaded(st.info)
                    else -> st
                }
            }
            activeId.value = null

            val loadedInfo = try {
                loadRuntimeWithFallback(target.info, path)
            } catch (t: Throwable) {
                states.value = states.value.map { st ->
                    if (st.info.id == modelId) ModelState.Downloaded(target.info) else st
                }
                throw t
            }

            states.value = states.value.map { st ->
                if (st.info.id == modelId) ModelState.Loaded(loadedInfo) else st
            }
            activeId.value = modelId
            runtimeNotifier.showLoaded(loadedInfo.displayName, loadedInfo.accelerator.name)
        }
    }

    private suspend fun loadRuntimeWithFallback(info: ModelInfo, path: String): ModelInfo {
        val systemPrompt = prefs.settings.first().systemPrompt
        val attempts = buildList {
            add(info)
            if (info.accelerator != Accelerator.CPU) add(info.copy(accelerator = Accelerator.CPU))
        }
        var lastError: Throwable? = null
        for (candidate in attempts.distinctBy { it.accelerator }) {
            try {
                runtime.load(
                    model = candidate,
                    modelPath = path,
                    supportImage = candidate.supportsVision,
                    systemPrompt = systemPrompt,
                )
                return candidate
            } catch (t: Throwable) {
                lastError = t
                runCatching { runtime.unload() }
                Log.w(TAG, "runtime load failed on ${candidate.accelerator}; trying next backend", t)
            }
        }
        throw lastError ?: IllegalStateException("Could not load model")
    }

    override suspend fun unload() {
        runtime.unload()
        states.value = states.value.map { st ->
            if (st is ModelState.Loaded) ModelState.Downloaded(st.info) else st
        }
        activeId.value = null
        runtimeNotifier.clear()
    }

    override suspend fun startDownload(modelId: String) {
        if (downloads[modelId]?.isActive == true) return
        val info = states.value.firstOrNull { it.info.id == modelId }?.info ?: return
        val source = info.source as? ModelSource.HuggingFace ?: return
        states.value = states.value.map { st ->
            if (st.info.id == modelId) ModelState.Downloading(info, 0L, 0L) else st
        }

        downloads[modelId] = scope.launch {
            DownloadForegroundService.acquire(
                context, info.displayName, "Preparing…", percent = 0,
            )
            try {
                downloadFromHuggingFace(info, source)
                notifier.done(modelId, info.displayName)
            } catch (c: CancellationException) {
                notifier.cancelled(modelId)
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "download failed for $modelId", t)
                val message = t.message ?: "error"
                // .part-файл сохраняем — позволит докачать после повторного нажатия.
                states.value = states.value.map { st ->
                    if (st.info.id == modelId) ModelState.Error(info, message) else st
                }
                notifier.error(modelId, info.displayName, message)
            } finally {
                downloads.remove(modelId)
                DownloadForegroundService.release(context)
            }
        }
    }

    private suspend fun downloadFromHuggingFace(info: ModelInfo, src: ModelSource.HuggingFace) =
        withContext(Dispatchers.IO) {
            val target = modelFile(info)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            val revision = src.revision.ifBlank { "main" }
            val urlStr = "https://huggingface.co/${src.repoId}/resolve/$revision/${src.file}?download=true"

            val savedToken = prefs.settings.first().hfToken
            val resumeFrom = if (tmp.exists()) tmp.length() else 0L

            // Пробуем без токена сначала. На 401 — повторяем с сохранённым токеном.
            val firstResp = openHfConnection(urlStr, token = null, rangeStart = resumeFrom)
            val conn: HttpURLConnection = when (val code = firstResp.responseCode) {
                in 200..299 -> firstResp
                401 -> {
                    firstResp.disconnect()
                    if (savedToken.isBlank()) {
                        throw IllegalStateException(
                            "HTTP 401: model requires authentication. " +
                                "Open Settings → Hugging Face and enter your token from huggingface.co/settings/tokens, " +
                                "then accept the model license."
                        )
                    }
                    val retry = openHfConnection(urlStr, token = savedToken, rangeStart = resumeFrom)
                    if (retry.responseCode in 200..299) retry
                    else {
                        val msg = "HTTP ${retry.responseCode}: Hugging Face token was rejected. " +
                            "Check your token in Settings and accept the model license on its page."
                        retry.disconnect()
                        throw IllegalStateException(msg)
                    }
                }
                403 -> {
                    firstResp.disconnect()
                    throw IllegalStateException(
                        "HTTP 403: model access has not been approved. " +
                            "Visit the model page on huggingface.co and accept its license."
                    )
                }
                else -> {
                    val msg = "HTTP $code: ${firstResp.responseMessage}"
                    firstResp.disconnect()
                    throw IllegalStateException(msg)
                }
            }
            try {
                // Если сервер не подтвердил Range (HTTP 206) — начинаем с нуля.
                val isPartial = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                val startOffset = if (isPartial) resumeFrom else 0L
                if (!isPartial && resumeFrom > 0L) {
                    Log.w(TAG, "server ignored Range; restarting from 0 for ${info.id}")
                }
                val remaining = conn.contentLengthLong.coerceAtLeast(0L)
                val total = (startOffset + remaining).coerceAtLeast(info.sizeBytes)

                FileOutputStream(tmp, isPartial /* append если докачка */).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = startOffset
                        var lastReport = 0L
                        var lastDone = startOffset
                        var lastTs = System.currentTimeMillis()
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 250) {
                                val dt = (now - lastTs).coerceAtLeast(1)
                                val bps = ((done - lastDone) * 1000L) / dt
                                states.value = states.value.map { st ->
                                    if (st.info.id == info.id)
                                        ModelState.Downloading(info.copy(sizeBytes = total), done, bps)
                                    else st
                                }
                                val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                                notifier.progress(info.id, info.displayName, pct, "${humanBytes(bps)}/s")
                                DownloadForegroundService.update(
                                    context, info.displayName,
                                    "${humanBytes(done)} / ${humanBytes(total)} · ${humanBytes(bps)}/s",
                                    pct,
                                )
                                lastReport = now
                                lastTs = now
                                lastDone = done
                            }
                        }
                        out.flush()
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                DownloadForegroundService.update(
                    context,
                    info.displayName,
                    "Verifying SHA-256…",
                    100,
                )
                val sha = sha256(target)
                val expected = info.expectedSha256
                if (!expected.isNullOrBlank() && !sha.equals(expected, ignoreCase = true)) {
                    target.delete()
                    hashSidecar(target).delete()
                    throw IllegalStateException("SHA-256 mismatch: file deleted; download the model again")
                }
                writeHashSidecar(target, sha)

                // Embedding models need a second file (SentencePiece tokenizer). Small, no resume.
                src.tokenizerFile?.let { tok ->
                    val tokDest = File(target.parentFile, tok.substringAfterLast('/'))
                    if (!tokDest.exists()) {
                        DownloadForegroundService.update(context, info.displayName, "Downloading tokenizer…", 100)
                        val tokUrl = "https://huggingface.co/${src.repoId}/resolve/$revision/$tok?download=true"
                        runCatching { downloadAuxFile(tokUrl, tokDest, null) }
                            .recoverCatching { downloadAuxFile(tokUrl, tokDest, savedToken.ifBlank { null }) }
                            .getOrThrow()
                    }
                }

                val finalInfo = info.copy(
                    sizeBytes = target.length(),
                    source = ModelSource.HuggingFace(src.repoId, src.file, src.revision, src.tokenizerFile),
                )
                states.value = states.value.map { st ->
                    if (st.info.id == info.id) ModelState.Downloaded(finalInfo) else st
                }
            } finally {
                conn.disconnect()
                // ВАЖНО: tmp НЕ удаляем при ошибке — нужен для resume.
                // Удаление tmp выполняется только в cancelDownload(reset=true) и delete().
            }
        }

    private fun openHfConnection(
        url: String,
        token: String?,
        rangeStart: Long = 0L,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LocalChat/0.1")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (rangeStart > 0L) {
                setRequestProperty("Range", "bytes=$rangeStart-")
                setRequestProperty("Accept-Encoding", "identity")
            }
            connect()
        }

    /** Download a small auxiliary file (e.g. tokenizer) in one shot — no resume/progress. */
    private fun downloadAuxFile(url: String, dest: File, token: String?) {
        val conn = openHfConnection(url, token, rangeStart = 0L)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} for ${dest.name}")
            }
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out -> conn.inputStream.use { it.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun cancelDownload(modelId: String) {
        downloads[modelId]?.cancel()
        downloads.remove(modelId)
        val info = states.value.firstOrNull { it.info.id == modelId }?.info ?: return
        states.value = states.value.map { st ->
            if (st.info.id == modelId) ModelState.Available(info) else st
        }
    }

    override suspend fun delete(modelId: String) {
        if (activeId.value == modelId) {
            runtime.unload()
            activeId.value = null
            runtimeNotifier.clear()
        }
        val info = states.value.firstOrNull { it.info.id == modelId }?.info ?: return
        runCatching {
            val file = modelFile(info)
            file.delete()
            hashSidecar(file).delete()
        }
        states.value = states.value.map { st ->
            if (st.info.id == modelId) ModelState.Available(info) else st
        }
    }

    override suspend fun deleteAll() {
        downloads.values.forEach { it.cancel() }
        downloads.clear()
        runtime.unload()
        activeId.value = null
        runtimeNotifier.clear()
        states.value.forEach { state ->
            val file = modelFile(state.info)
            runCatching { file.parentFile?.deleteRecursively() }
        }
        states.value = states.value.map { ModelState.Available(it.info) }
        DownloadForegroundService.release(context)
    }

    override suspend fun updateConfig(
        modelId: String,
        contextSize: Int,
        maxTokens: Int,
        topK: Int,
        topP: Float,
        temperature: Float,
        accelerator: Accelerator,
        enableSpeculative: Boolean,
    ) {
        val before = states.value.firstOrNull { it.info.id == modelId }
        val wasLoaded = before is ModelState.Loaded
        val backendChanged = before?.info?.accelerator != accelerator
        val contextChanged = before?.info?.contextSize != contextSize
        val maxTokensChanged = before?.info?.maxTokens != maxTokens
        val speculativeChanged = before?.info?.enableSpeculative != enableSpeculative
        val samplerChanged = before?.info?.let {
            it.topK != topK || it.topP != topP || it.temperature != temperature
        } == true

        states.value = states.value.map { st ->
            if (st.info.id == modelId) {
                val newInfo = st.info.copy(
                    contextSize = contextSize,
                    maxTokens = maxTokens,
                    topK = topK,
                    topP = topP,
                    temperature = temperature,
                    accelerator = accelerator,
                    enableSpeculative = enableSpeculative,
                )
                when (st) {
                    is ModelState.Available -> ModelState.Available(newInfo)
                    is ModelState.Loading -> ModelState.Loading(newInfo)
                    is ModelState.Downloading -> ModelState.Downloading(newInfo, st.bytesDownloaded, st.bytesPerSec)
                    is ModelState.Downloaded -> ModelState.Downloaded(newInfo)
                    is ModelState.Loaded -> ModelState.Loaded(newInfo)
                    is ModelState.Error -> ModelState.Error(newInfo, st.message)
                }
            } else st
        }

        prefs.setModelConfig(
            modelId,
            PersistedModelConfig(
                contextSize = contextSize,
                maxTokens = maxTokens,
                topK = topK,
                topP = topP,
                temperature = temperature,
                accelerator = accelerator.name,
            ),
        )

        if (!wasLoaded) return
        // Bаckend / context / maxTokens / speculative требуют пересоздания Engine.
        // Sampler (topK/topP/temperature) — только Conversation; делаем дешёвый reset.
        val needsFullReload = backendChanged || contextChanged || maxTokensChanged || speculativeChanged
        if (needsFullReload) {
            runCatching { load(modelId) }
        } else if (samplerChanged) {
            val newInfo = states.value.firstOrNull { it.info.id == modelId }?.info ?: return
            runCatching { runtime.resetConversation(model = newInfo) }
        }
    }

    override suspend fun addCustom(
        repoId: String,
        file: String,
        displayName: String,
        supportsVision: Boolean,
        supportsThinking: Boolean,
        contextSize: Int,
        accelerator: Accelerator,
    ) {
        val id = "custom-${repoId.replace('/', '-')}-${file.substringBeforeLast('.')}"
        if (states.value.any { it.info.id == id }) return
        val info = ModelInfo(
            id = id,
            displayName = displayName,
            sizeBytes = 0L,
            supportsVision = supportsVision,
            supportsThinking = supportsThinking,
            source = ModelSource.HuggingFace(repoId, file),
            contextSize = contextSize,
            maxTokens = contextSize.coerceAtMost(DEFAULT_OUTPUT_TOKENS).coerceAtLeast(1),
            accelerator = accelerator,
        )
        states.value = states.value + ModelState.Available(info)
    }

    private fun modelFile(info: ModelInfo): File {
        val dir = File(context.filesDir, "models/${info.id}")
        val name = (info.source as? ModelSource.HuggingFace)?.file ?: "${info.id}.task"
        return File(dir, name)
    }

    private fun applyPersistedConfig(info: ModelInfo, config: PersistedModelConfig?): ModelInfo {
        if (config == null) return info
        return info.copy(
            contextSize = config.contextSize.coerceIn(256, 128_000),
            maxTokens = config.maxTokens.coerceIn(1, config.contextSize.coerceAtLeast(1)),
            topK = config.topK.coerceAtLeast(1),
            topP = config.topP.coerceIn(0f, 1f),
            temperature = config.temperature.coerceAtLeast(0f),
            accelerator = runCatching { Accelerator.valueOf(config.accelerator) }.getOrDefault(info.accelerator),
            enableSpeculative = false,
        )
    }

    private fun ModelState.withInfo(info: ModelInfo): ModelState = when (this) {
        is ModelState.Available -> ModelState.Available(info)
        is ModelState.Loading -> ModelState.Loading(info)
        is ModelState.Downloading -> ModelState.Downloading(info, bytesDownloaded, bytesPerSec)
        is ModelState.Downloaded -> ModelState.Downloaded(info)
        is ModelState.Loaded -> ModelState.Loaded(info)
        is ModelState.Error -> ModelState.Error(info, message)
    }

    private suspend fun validateModelFile(info: ModelInfo, file: File) = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) {
            throw IllegalStateException("Model file is empty or missing")
        }
        val sidecar = hashSidecar(file)
        val expected = info.expectedSha256?.takeIf { it.isNotBlank() }
        val recorded = sidecar.takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.split(Regex("\\s+"))
            .orEmpty()
        val recordedSha = recorded.getOrNull(0)?.takeIf { it.length == 64 }
        val recordedSize = recorded.getOrNull(1)?.toLongOrNull()

        if (recordedSize != null && recordedSize != file.length()) {
            throw IllegalStateException("Model file size changed. Delete and download the model again")
        }
        if (expected != null || recordedSha != null) {
            val actual = sha256(file)
            if (expected != null && !actual.equals(expected, ignoreCase = true)) {
                throw IllegalStateException("Model checksum mismatch. Delete and download the model again")
            }
            if (recordedSha != null && !actual.equals(recordedSha, ignoreCase = true)) {
                throw IllegalStateException("Model checksum changed. Delete and download the model again")
            }
            if (recordedSha == null) writeHashSidecar(file, actual)
        }
    }

    private fun hashSidecar(file: File): File = File(file.parentFile, file.name + ".sha256")

    private fun writeHashSidecar(file: File, sha: String) {
        hashSidecar(file).writeText("$sha ${file.length()} ${file.name}\n")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun initial(): List<ModelState> {
        // Curated LiteRT-LM models from the AI Edge community on Hugging Face, plus a small public
        // Qwen 0.5B as a no-token starter/smoke-test. litertlm accepts both .litertlm and .task.
        // google/gemma-3n-* are gated on HF (require a token + license acceptance).
        // litert-community/* — публичные, скачиваются без токена.
        val items = listOf(
            ModelInfo(
                id = "qwen3-0-6b-instruct",
                displayName = "Qwen 3 0.6B Instruct (offline)",
                supportsTools = false,
                sizeBytes = 344_437_808,
                expectedSha256 = "e3e290109da4388d65a17510a0c66af91c8039f52d2c465868dbc43c09a776cf",
                accelerator = Accelerator.GPU,
                source = ModelSource.HuggingFace(
                    repoId = "litert-community/Qwen3-0.6B",
                    file = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
                    revision = "111c6fb56c33a7efa8e59ffbefd6afaa39b9ad2c",
                ),
                contextSize = 4096,
                maxTokens = 512,
                topK = 20, topP = 0.8f, temperature = 0.4f,
            ),
            ModelInfo(
                id = "gemma-4-e2b-it",
                displayName = "Gemma 4 E2B IT",
                sizeBytes = 2_588_147_712,
                estimatedRamBytes = 0,
                supportsVision = true,
                supportsAudio = true,
                supportsThinking = true,
                accelerator = Accelerator.GPU,
                source = ModelSource.HuggingFace(
                    repoId = "litert-community/gemma-4-E2B-it-litert-lm",
                    file = "gemma-4-E2B-it.litertlm",
                    revision = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
                ),
                contextSize = DEFAULT_CONTEXT_TOKENS,
                maxTokens = DEFAULT_OUTPUT_TOKENS,
                topK = 64, topP = 0.95f, temperature = 1.0f,
            ),
            ModelInfo(
                id = "gemma-4-e4b-it",
                displayName = "Gemma 4 E4B IT",
                sizeBytes = 3_659_530_240,
                supportsVision = true,
                supportsAudio = true,
                supportsThinking = true,
                accelerator = Accelerator.GPU,
                source = ModelSource.HuggingFace(
                    repoId = "litert-community/gemma-4-E4B-it-litert-lm",
                    file = "gemma-4-E4B-it.litertlm",
                    revision = "28299f30ee4d43294517a4ac93abd6163412f07f",
                ),
                contextSize = DEFAULT_CONTEXT_TOKENS,
                maxTokens = DEFAULT_OUTPUT_TOKENS,
                topK = 64, topP = 0.95f, temperature = 1.0f,
            ),
            ModelInfo(
                id = "gemma-3n-e2b-it",
                displayName = "Gemma 3n E2B IT",
                sizeBytes = 3_655_827_456,
                supportsVision = true,
                supportsAudio = true,
                accelerator = Accelerator.GPU,
                source = ModelSource.HuggingFace(
                    repoId = "google/gemma-3n-E2B-it-litert-lm",
                    file = "gemma-3n-E2B-it-int4.litertlm",
                    revision = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
                ),
                contextSize = DEFAULT_CONTEXT_TOKENS, maxTokens = DEFAULT_OUTPUT_TOKENS,
                topK = 64, topP = 0.95f, temperature = 1.0f,
            ),
            ModelInfo(
                id = "gemma-3n-e4b-it",
                displayName = "Gemma 3n E4B IT",
                sizeBytes = 4_919_541_760,
                supportsVision = true,
                supportsAudio = true,
                accelerator = Accelerator.GPU,
                source = ModelSource.HuggingFace(
                    repoId = "google/gemma-3n-E4B-it-litert-lm",
                    file = "gemma-3n-E4B-it-int4.litertlm",
                    revision = "297ed75955702dec3503e00c2c2ecbbf475300bc",
                ),
                contextSize = DEFAULT_CONTEXT_TOKENS, maxTokens = DEFAULT_OUTPUT_TOKENS,
                topK = 64, topP = 0.95f, temperature = 1.0f,
            ),
            ModelInfo(
                id = "gemma3-1b-it",
                displayName = "Gemma 3 1B IT (q4)",
                sizeBytes = 584_417_280,
                accelerator = Accelerator.GPU,
                source = ModelSource.HuggingFace(
                    repoId = "litert-community/Gemma3-1B-IT",
                    file = "gemma3-1b-it-int4.litertlm",
                    revision = "42d538a932e8d5b12e6b3b455f5572560bd60b2c",
                ),
                contextSize = DEFAULT_CONTEXT_TOKENS, maxTokens = DEFAULT_OUTPUT_TOKENS,
                topK = 64, topP = 0.95f, temperature = 1.0f,
            ),
            ModelInfo(
                id = "qwen2-5-1-5b-instruct",
                displayName = "Qwen 2.5 1.5B Instruct (q8)",
                sizeBytes = 1_597_931_520,
                accelerator = Accelerator.GPU,
                source = ModelSource.HuggingFace(
                    repoId = "litert-community/Qwen2.5-1.5B-Instruct",
                    file = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                    revision = "19edb84c69a0212f29a6ef17ba0d6f278b6a1614",
                ),
                contextSize = DEFAULT_CONTEXT_TOKENS, maxTokens = DEFAULT_OUTPUT_TOKENS,
                topK = 20, topP = 0.8f, temperature = 0.7f,
            ),
            ModelInfo(
                id = "deepseek-r1-distill-qwen-1-5b",
                displayName = "DeepSeek R1 Distill Qwen 1.5B (q8)",
                sizeBytes = 1_833_451_520,
                accelerator = Accelerator.GPU,
                source = ModelSource.HuggingFace(
                    repoId = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
                    file = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                    revision = "e34bb88632342d1f9640bad579a45134eb1cf988",
                ),
                contextSize = DEFAULT_CONTEXT_TOKENS, maxTokens = DEFAULT_OUTPUT_TOKENS,
                topK = 64, topP = 0.95f, temperature = 1.0f,
            ),
            // ── Embedding models (semantic RAG) ──────────────────────────────────────────────
            ModelInfo(
                id = "embeddinggemma-300m",
                displayName = "EmbeddingGemma 300M (multilingual)",
                // seq512 mixed-precision .tflite ≈ 180 MB + sentencepiece tokenizer ≈ 4.7 MB.
                sizeBytes = 185_000_000,
                accelerator = Accelerator.CPU,
                supportsTools = false,
                kind = ModelKind.Embedding,
                source = ModelSource.HuggingFace(
                    repoId = "litert-community/embeddinggemma-300m",
                    file = "embeddinggemma-300M_seq512_mixed-precision.tflite",
                    tokenizerFile = "sentencepiece.model",
                ),
                contextSize = 2048,
                maxTokens = 1,
            ),
        )
        // Восстановим Downloaded для уже скачанных файлов.
        return items.map { info ->
            val f = modelFile(info)
            if (f.exists() && f.length() > 0) {
                ModelState.Downloaded(info.copy(sizeBytes = f.length()))
            } else {
                ModelState.Available(info)
            }
        }
    }
}

private const val DEFAULT_CONTEXT_TOKENS = 8192
private const val DEFAULT_OUTPUT_TOKENS = 2048


