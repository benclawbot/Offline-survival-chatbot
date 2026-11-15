package com.localchat.runtime

/** Chat-template family, inferred from the rendered prompt markers (see [ModelTemplateProbe]). */
enum class TemplateFamily { CHATML, GEMMA, UNKNOWN }

/**
 * Reads the chat-template family from what the ENGINE actually renders for a probe message —
 * not from the model's file name. Validated on the desktop harness against real bundles:
 * ChatML bundles (Qwen/Llama/Phi) emit `<|im_start|>…<|im_end|>`; Gemma bundles emit
 * `<|turn>…<turn|>` (Gemma 4) or `<start_of_turn>…<end_of_turn>` (Gemma 3). This drives the
 * tool dialect and the reasoning-channel markers.
 *
 * Note: vision is NOT inferred here. The template inserts image/audio placeholder tokens even
 * on a config without an encoder, and the encoder lives inside the single .litertlm file —
 * it loads only when EngineConfig.visionBackend is set. So vision capability comes from the
 * curated model flag + a configured vision backend (same as google-ai-edge/gallery), not from
 * parsing the template.
 */
object ModelTemplateProbe {

    fun classifyFamily(renderedTemplate: String): TemplateFamily {
        val t = renderedTemplate
        return when {
            t.contains("<|im_start|>") -> TemplateFamily.CHATML
            t.contains("<|turn>") || t.contains("<start_of_turn>") -> TemplateFamily.GEMMA
            else -> TemplateFamily.UNKNOWN
        }
    }
}
