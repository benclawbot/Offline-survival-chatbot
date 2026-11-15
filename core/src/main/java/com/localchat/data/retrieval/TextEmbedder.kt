package com.localchat.data.retrieval

/** What a piece of text is being embedded FOR — embedding models score query↔document better
 *  when each side carries the right task instruction (EmbeddingGemma uses asymmetric prefixes). */
enum class EmbedKind { QUERY, DOCUMENT }

/**
 * Turns text into dense vectors for semantic retrieval. Kept as a pure interface so the retriever
 * and its harness tests never touch Android or a real model — the on-device implementation
 * ([com.localchat.data.retrieval] Android side, wrapping EmbeddingGemma) lives elsewhere and
 * a deterministic fake is used in tests.
 *
 * Implementations MUST return L2-normalized vectors of length [dims] (so cosine similarity is a
 * plain dot product), one per input text, in order. When [isReady] is false the model isn't
 * available (e.g. not downloaded yet) and callers fall back to lexical-only retrieval.
 */
interface TextEmbedder {
    /** Vector length (EmbeddingGemma: 768 truncated via Matryoshka to a smaller dim, e.g. 128). */
    val dims: Int

    /** True once the model is loaded and embedding can succeed. */
    val isReady: Boolean

    /** Embed [texts] for the given [kind]; returns L2-normalized vectors aligned with the input. */
    suspend fun embed(texts: List<String>, kind: EmbedKind): List<FloatArray>
}
