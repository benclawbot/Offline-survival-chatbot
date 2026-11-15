package com.localchat.data.retrieval

import kotlin.math.ln

/**
 * Pure-Kotlin BM25+ scorer over an in-memory corpus of [IndexedChunk]s.
 *
 * Why BM25+ and not vanilla BM25:
 * - Standard BM25 over-penalises long documents because the length normaliser
 *   in the denominator is unbounded; long chunks (e.g., a 1500-token Wikipedia
 *   section) get effectively disqualified vs. a 50-token snippet.
 * - BM25+ adds a constant lower-bound δ on the saturated term-frequency, which
 *   restores ranking stability across our heterogenous corpus (chat messages,
 *   tool_results, PDF chunks, all in one pile).
 *
 * Tuning: k1=0.9, b=0.4, δ=1.0 — values tuned for short conversational queries
 * and mixed RU/EN content (per the Gemini research).
 *
 * Title boost: filename + section heading scored as a separate field with weight
 * 2.5×, à la BM25F. Implemented inline rather than as full BM25F because we only
 * have two fields and the simpler form is easier to reason about.
 */
internal class Bm25PlusScorer(
    private val corpus: List<IndexedChunk>,
    private val k1: Double = 0.9,
    private val b: Double = 0.4,
    private val delta: Double = 1.0,
    private val titleBoost: Double = 2.5,
) {
    private val avgDocLen: Double = corpus.takeIf { it.isNotEmpty() }
        ?.map { it.tokens.size }?.average() ?: 1.0
    private val docFreq: Map<String, Int> = run {
        val df = HashMap<String, Int>()
        for (chunk in corpus) {
            for (t in chunk.tokens.toSet()) df[t] = (df[t] ?: 0) + 1
        }
        df
    }
    private val n = corpus.size.coerceAtLeast(1)

    fun score(queryTokens: List<String>): List<Bm25Hit> {
        if (queryTokens.isEmpty() || corpus.isEmpty()) return emptyList()
        val out = ArrayList<Bm25Hit>(corpus.size)
        for (chunk in corpus) {
            val docLen = chunk.tokens.size
            val lenNorm = 1 - b + b * docLen / avgDocLen
            val tfMap = chunk.termFreq
            val titleSet = chunk.titleTokenSet
            var s = 0.0
            for (q in queryTokens) {
                val df = docFreq[q] ?: 0
                val inTitle = q in titleSet
                if (df == 0 && !inTitle) continue
                // Synthetic IDF for title-only matches: title tokens are NOT in
                // the corpus's docFreq map (they're metadata), so without this
                // a title-only match has idf=ln(1)=0 and the boost vanishes.
                val idf = if (df == 0) ln(n + 1.0)
                else ln((n - df + 0.5) / (df + 0.5) + 1.0)
                val tf = tfMap[q] ?: 0
                if (tf == 0 && !inTitle) continue
                // Treat title hits as tf=1 for the saturation term, then layer
                // an explicit titleBoost multiplier on top so a title-only
                // match still beats a body-only match at the same TF.
                val effectiveTf = if (tf == 0 && inTitle) 1 else tf
                val saturated = (effectiveTf * (k1 + 1.0)) / (effectiveTf + k1 * lenNorm) + delta
                var contribution = idf * saturated
                if (inTitle) contribution *= titleBoost
                s += contribution
            }
            if (s > 0) out.add(Bm25Hit(chunk, s))
        }
        return out.sortedByDescending { it.score }
    }
}

internal data class Bm25Hit(val chunk: IndexedChunk, val score: Double)
