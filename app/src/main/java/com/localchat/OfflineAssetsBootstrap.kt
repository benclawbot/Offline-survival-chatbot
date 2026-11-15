package com.localchat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** Installs the offline corpus and model from APK assets into app-private storage. */
object OfflineAssetsBootstrap {
    sealed interface State {
        data object Preparing : State
        data class Progress(val label: String, val fraction: Float) : State
        data class Failed(val message: String) : State
        data object Ready : State
    }

    private val mutableState = MutableStateFlow<State>(State.Preparing)
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Synchronized fun start(context: Context) {
        if (started && mutableState.value !is State.Failed) return
        started = true
        val app = context.applicationContext
        scope.launch {
            try {
                val modelDir = File(app.filesDir, "models/qwen2-0-5b-instruct")
                val model = File(modelDir, MODEL_FILE)
                if (model.length() != MODEL_BYTES) {
                    modelDir.mkdirs()
                    val part = File(modelDir, "$MODEL_FILE.part")
                    app.assets.open(MODEL_FILE).use { input ->
                        FileOutputStream(part).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var copied = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                mutableState.value = State.Progress("Preparing local model", copied.toFloat() / MODEL_BYTES)
                            }
                            output.fd.sync()
                        }
                    }
                    check(part.length() == MODEL_BYTES) { "Bundled model copy is incomplete" }
                    check(part.renameTo(model)) { "Could not install bundled model" }
                }

                val library = File(app.filesDir, "survival/Library")
                val marker = File(library, ".installed")
                if (marker.readTextOrNull() != LIBRARY_SHA || !File(library, "index.sqlite").isFile) {
                    library.deleteRecursively()
                    library.mkdirs()
                    ZipInputStream(app.assets.open("library-lite.zip").buffered()).use { zip ->
                        val buffer = ByteArray(1024 * 1024)
                        var entry = zip.nextEntry
                        var extracted = 0L
                        while (entry != null) {
                            val target = File(library, entry.name).canonicalFile
                            check(target.path.startsWith(library.canonicalPath + File.separator)) { "Invalid library archive" }
                            if (entry.isDirectory) target.mkdirs() else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { output ->
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                        extracted += count
                                        mutableState.value = State.Progress("Unpacking Essentials reference library", (extracted.toDouble() / LIBRARY_BYTES).toFloat().coerceIn(0f, .99f))
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                    check(File(library, "index.sqlite").length() > 100_000_000L) { "Library index is missing or incomplete" }
                    marker.writeText(LIBRARY_SHA)
                }
                mutableState.value = State.Ready
            } catch (t: Throwable) {
                mutableState.value = State.Failed(t.message ?: "Offline setup failed")
            }
        }
    }

    private const val MODEL_FILE = "Qwen2_0.5B_Instruct.litertlm"
    private const val MODEL_BYTES = 647_377_840L
    // Sum of uncompressed file sizes in the official Essentials archive.
    private const val LIBRARY_BYTES = 3_234_164_481L
    private const val LIBRARY_SHA = "0e9fc46df8288c1aa9a81b87386acd224963b8a1b2fd44d51078089d3dee8686"

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}
