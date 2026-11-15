package com.localchat.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localchat.domain.model.Accelerator
import com.localchat.domain.model.ModelInfo
import com.localchat.domain.model.ModelSource
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiteRtRuntimeSmokeTest {
    @Test
    fun loadsModelAndStreamsTokens() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString("modelPath") ?: "/data/local/tmp/localchat-smoke-model.task"
        assertTrue(
            "Push a LiteRT-LM model to $modelPath before running this test.",
            File(modelPath).isFile,
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = LiteRtRuntimeImpl(context)
        val model = ModelInfo(
            id = "runtime-smoke",
            displayName = "Runtime Smoke Model",
            sizeBytes = File(modelPath).length(),
            accelerator = Accelerator.CPU,
            source = ModelSource.LocalFile(modelPath),
            contextSize = 1280,
            maxTokens = 64,
            topK = 20,
            topP = 0.8f,
            temperature = 0.2f,
        )

        var error: String? = null
        val response = StringBuilder()
        var done = false

        try {
            runtime.load(
                model = model,
                modelPath = modelPath,
                supportImage = false,
                systemPrompt = "You are a concise assistant.",
            )

            withTimeout(120_000) {
                runtime.stream("Say OK in one short sentence.").collect { chunk ->
                    when (chunk) {
                        is InferenceChunk.Token -> response.append(chunk.text)
                        is InferenceChunk.Done -> done = true
                        is InferenceChunk.Error -> error = chunk.message
                    }
                }
            }
        } finally {
            runtime.unload()
        }

        assertNull(error)
        assertTrue("Runtime did not finish streaming.", done)
        assertTrue("Runtime streamed an empty response.", response.toString().isNotBlank())
    }
}
