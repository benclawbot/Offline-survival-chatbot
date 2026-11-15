package com.localchat.runtime

object ContextWindow {

    /** Approx 4 chars per token for Latin-heavy text; safe upper bound for cyrillic too. */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    /**
     * Per-message overhead from the chat template (role header, separators, end-of-turn).
     * Empirically Gemma/Qwen LiteRT templates add ~6–10 tokens per turn — keep a generous 12.
     */
    const val PER_MESSAGE_OVERHEAD = 12

    /** Reserve for image tokens (vision models embed image as ~256–576 tokens, take the larger). */
    const val IMAGE_TOKEN_RESERVE = 600

    /** Hard floor — we never go below this even if model.contextSize is tiny. */
    private const val MIN_BUDGET = 256

    fun inputBudget(contextSize: Int, maxOutputTokens: Int): Int =
        (contextSize - maxOutputTokens).coerceAtLeast(MIN_BUDGET)

    fun trimText(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        if (estimateTokens(text) <= maxTokens) return text
        val maxChars = (maxTokens * 4).coerceAtLeast(0)
        val marker = "\n\n[context trimmed to fit the model window]"
        val bodyChars = (maxChars - marker.length).coerceAtLeast(0)
        return text.take(bodyChars).trimEnd() + marker
    }

    fun trimHistory(
        history: List<RuntimeChatMessage>,
        contextSize: Int,
        maxOutputTokens: Int,
    ): List<RuntimeChatMessage> {
        // Reserve ~30% of the input window for the current user prompt + RAG. The remaining
        // 78% is what we have for prior turns. This matches gallery's empirical split.
        val budget = (inputBudget(contextSize, maxOutputTokens) * 0.78f).toInt()
            .coerceAtLeast(MIN_BUDGET)
        if (history.isEmpty()) return history

        val tokens = IntArray(history.size) { estimateTokens(history[it].text) + PER_MESSAGE_OVERHEAD }
        val total = tokens.sum()
        if (total <= budget) return history

        var used = 0
        val tail = ArrayDeque<RuntimeChatMessage>()
        for (i in history.indices.reversed()) {
            val cost = tokens[i]
            if (used + cost > budget) break
            tail.addFirst(history[i])
            used += cost
        }
        // Never return empty — model needs at least the most recent user turn.
        if (tail.isEmpty() && history.isNotEmpty()) tail.addFirst(history.last())
        return tail.toList()
    }
}

