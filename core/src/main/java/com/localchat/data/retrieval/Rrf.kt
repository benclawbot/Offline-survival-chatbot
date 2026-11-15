package com.localchat.data.retrieval

/**
 * Reciprocal Rank Fusion (RRF). Merges several rankings into one without needing
 * score calibration between scorers (BM25 scores and Jaccard similarity live on
 * different scales).
 *
 *     RRF(d) = Σ_m  1 / (k + rank_m(d))
 *
 * `k = 60` is the canonical value (Cormack et al., TREC 2009). Lower k → top
 * ranks dominate; higher k → bottom ranks contribute more. 60 is the sweet
 * spot for our 20-candidate cap per ranker.
 */
internal object Rrf {
    private const val K = 60.0

    fun fuse(rankings: List<List<String>>): List<RrfHit> {
        val acc = HashMap<String, Double>()
        for (ranking in rankings) {
            for ((idx, id) in ranking.withIndex()) {
                val rank = idx + 1
                acc.merge(id, 1.0 / (K + rank)) { a, b -> a + b }
            }
        }
        return acc.entries.sortedByDescending { it.value }
            .map { RrfHit(chunkId = it.key, score = it.value) }
    }
}

internal data class RrfHit(val chunkId: String, val score: Double)
