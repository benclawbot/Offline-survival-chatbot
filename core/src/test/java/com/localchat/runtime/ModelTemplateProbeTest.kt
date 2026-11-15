package com.localchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Harness for the pure capability-from-engine helpers. The fixtures are the ACTUAL strings the
 * litertlm-jvm engine rendered for these bundles on the desktop probe (see :harness --probe),
 * so the classifier is tested against real output, not assumptions.
 */
class ModelTemplateProbeTest {

    // Real render from qwen3-0.6b.litertlm (--probe): plain ChatML.
    private val qwen3Render = """
        <|im_start|>system
        You are a helpful assistant.<|im_end|>
        <|im_start|>user
        Hello there<|im_end|>
        <|im_start|>assistant
    """.trimIndent()

    // Real render from gemma-4-e2b-it.litertlm (--probe): Gemma 4 turn markers.
    private val gemma4Render = """
        <|turn>system
        You are a helpful assistant.<turn|>
        <|turn>user
        Hello there<turn|>
        <|turn>model
    """.trimIndent()

    @Test
    fun `classifies ChatML bundle (qwen3) as CHATML`() {
        assertEquals(TemplateFamily.CHATML, ModelTemplateProbe.classifyFamily(qwen3Render))
    }

    @Test
    fun `classifies Gemma 4 turn template as GEMMA`() {
        assertEquals(TemplateFamily.GEMMA, ModelTemplateProbe.classifyFamily(gemma4Render))
    }

    @Test
    fun `classifies Gemma 3 start_of_turn template as GEMMA`() {
        val gemma3 = "<start_of_turn>user\nHello<end_of_turn>\n<start_of_turn>model\n"
        assertEquals(TemplateFamily.GEMMA, ModelTemplateProbe.classifyFamily(gemma3))
    }

    @Test
    fun `unknown markup classifies as UNKNOWN`() {
        assertEquals(TemplateFamily.UNKNOWN, ModelTemplateProbe.classifyFamily("USER: hi\nASSISTANT:"))
    }
}
