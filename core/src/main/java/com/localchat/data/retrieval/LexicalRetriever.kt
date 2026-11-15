package com.localchat.data.retrieval

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public façade over the retrieval pipeline. Holds an in-memory corpus of chunks
 * (small enough on-device — chunks are short text + a few hundred tokens each)
 * and serves ranked results to the agent.
 *
 * Pipeline at query time:
 *
 *  1. Tokenise + trigram the query.
 *  2. BM25+ ranks the corpus by exact-match term overlap; take top 20.
 *  3. Trigram-Jaccard ranks by character overlap (catches typos / inflections);
 *     take top 20.
 *  4. RRF (k=60) fuses the two rankings into one ordered list.
 *  5. [BudgetAllocator] picks chunks under [budgetTokens], guaranteeing each
 *     surviving doc a per-doc minimum.
 *
 * Why both BM25 and trigram-Jaccard:
 * - BM25 is excellent for whole-word matches (English questions over English
 *   docs).
 * - Trigram-Jaccard fixes the two big BM25 failure modes for our content:
 *   typos in the query ("логистчиеская" vs "логистическая") and Russian
 *   morphology ("поиск" / "поиска" / "поиском" share trigrams).
 * RRF merges them without needing to calibrate BM25 scores against Jaccard.
 */
class LexicalRetriever @Inject constructor() {

    // Concurrent: indexed from the agent loop (Dispatchers.Default) and the send/
    // attach pipeline (Dispatchers.IO) while query() reads — a plain map would risk
    // ConcurrentModificationException. CHM's weakly-consistent iterators + per-bucket
    // writes make indexing/querying safe without coarse locking. Insertion order is
    // irrelevant here (results are ranked by score).
    private val chunks: MutableMap<String, IndexedChunk> = java.util.concurrent.ConcurrentHashMap()

    // Per-chunk dense vectors (L2-normalized), keyed by chunk id "$docId#$ord". Populated
    // asynchronously by the embedding layer; absent until/unless embeddings are computed, so
    // semantic ranking simply doesn't contribute when empty (lexical-only fallback).
    private val vectors: MutableMap<String, FloatArray> = java.util.concurrent.ConcurrentHashMap()

    // Bumps on every corpus mutation. Reactive consumers (the context meter) observe this so they
    // recompute AFTER async (re)indexing completes — the corpus itself isn't a Flow.
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun bump() { _version.value = _version.value + 1 }

    /** Number of chunks currently in the corpus. */
    val size: Int get() = chunks.size

    /** Approx token total of indexed chunks (optionally filtered by docId) — for the context meter. */
    fun indexedTokens(docFilter: ((String) -> Boolean)? = null): Int =
        chunks.values.asSequence()
            .filter { docFilter == null || docFilter(it.docId) }
            .sumOf { it.estimatedTokens }

    fun clear() { chunks.clear(); vectors.clear(); bump() }

    /** Attach precomputed vectors (chunk id → L2-normalized vector). Called by the embedding layer. */
    fun putVectors(items: Map<String, FloatArray>) {
        if (items.isEmpty()) return
        vectors.putAll(items)
        bump()
    }

    /** Chunk id → text for [docId], in document order — the embedding layer embeds these. */
    fun chunkEntries(docId: String): List<Pair<String, String>> =
        chunks.values.asSequence()
            .filter { it.docId == docId }
            .sortedBy { it.ordinal }
            .map { it.id to it.text }
            .toList()

    /** True if [docId] already has at least one vector (skip re-embedding on rehydrate). */
    fun hasVectors(docId: String): Boolean = vectors.keys.any { it.startsWith("$docId#") }

    /** Add or replace a chunk by [IndexedChunk.id]. */
    fun addChunk(chunk: IndexedChunk) {
        chunks[chunk.id] = chunk
        bump()
    }

    fun addChunks(items: Collection<IndexedChunk>) {
        for (c in items) chunks[c.id] = c
        bump()
    }

    /** Drop every chunk belonging to [docId]. Used when a document is removed. */
    fun removeDoc(docId: String) {
        // Collect keys first, then remove by key — safe on ConcurrentHashMap, unlike
        // mutating the values() view while query() may be iterating it.
        val keys = chunks.filterValues { it.docId == docId }.keys.toList()
        keys.forEach { chunks.remove(it) }
        vectors.keys.filter { it.startsWith("$docId#") }.forEach { vectors.remove(it) }
        bump()
    }

    /**
     * Index [text] under a synthetic doc with the given [docId] and [docTitle].
     * The text is sentence-chunked first, then each chunk is added to the corpus.
     */
    fun indexDocument(
        docId: String,
        docTitle: String,
        text: String,
        sectionHeading: String? = null,
        targetTokens: Int = 350,
    ): Int {
        removeDoc(docId)
        val pieces = SentenceChunker.chunk(text, targetTokens = targetTokens)
        pieces.forEachIndexed { ord, body ->
            addChunk(
                IndexedChunk.of(
                    id = "$docId#$ord",
                    docId = docId,
                    docTitle = docTitle,
                    ordinal = ord,
                    text = body,
                    sectionHeading = sectionHeading,
                )
            )
        }
        return pieces.size
    }

    /**
     * Run the full pipeline. Returns chunks ordered by RRF score, capped to
     * fit [budgetTokens] across docs.
     *
     * [docFilter], if non-null, restricts the search to chunks whose [IndexedChunk.docId]
     * matches the predicate — used by the `retriever_search` tool when the model
     * scopes the query to a specific filename.
     */
    fun query(
        text: String,
        budgetTokens: Int = 1_500,
        topK: Int = 6,
        candidatePoolPerScorer: Int = 20,
        docFilter: ((String) -> Boolean)? = null,
        queryVector: FloatArray? = null,
    ): List<RetrievalHit> {
        if (text.isBlank() || chunks.isEmpty()) return emptyList()
        val pool = if (docFilter == null) chunks.values.toList()
        else chunks.values.filter { docFilter(it.docId) || docFilter(it.docTitle) }
        if (pool.isEmpty()) return emptyList()

        val qTokens = Tokenizer.words(text)
        val qTrigrams = Tokenizer.trigrams(text).toSet()

        // Lexical rankings: exact term overlap (BM25+) and character overlap (trigram-Jaccard).
        val bm25 = Bm25PlusScorer(pool).score(qTokens)
            .take(candidatePoolPerScorer)
        val trigram = pool.asSequence()
            .map { it to jaccard(qTrigrams, it.trigrams) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(candidatePoolPerScorer)
            .toList()

        // Semantic ranking: cosine (= dot, vectors are L2-normalized) against chunk embeddings.
        // Only when a query vector is supplied AND we have chunk vectors → otherwise lexical-only.
        val semantic: List<IndexedChunk> = if (queryVector != null && vectors.isNotEmpty()) {
            pool.asSequence()
                .mapNotNull { c -> vectors[c.id]?.let { c to VectorMath.dot(queryVector, it) } }
                .sortedByDescending { it.second }
                .take(candidatePoolPerScorer)
                .map { it.first }
                .toList()
        } else emptyList()

        val byId = LinkedHashMap<String, IndexedChunk>().apply {
            bm25.forEach { put(it.chunk.id, it.chunk) }
            trigram.forEach { (c, _) -> put(c.id, c) }
            semantic.forEach { put(it.id, it) }
        }
        if (byId.isEmpty()) return emptyList()

        // Reciprocal-rank fusion across whichever rankings produced candidates. RRF needs no score
        // calibration between lexical and cosine — it fuses by rank, so the three are comparable.
        val rankings = buildList {
            add(bm25.map { it.chunk.id })
            add(trigram.map { (c, _) -> c.id })
            if (semantic.isNotEmpty()) add(semantic.map { it.id })
        }
        val rrf = Rrf.fuse(rankings)
        val ranked = rrf.mapNotNull { hit ->
            byId[hit.chunkId]?.let { RankedChunk(it, hit.score) }
        }
        val selected = BudgetAllocator.select(ranked, budgetTokens)
            .take(topK)
        return selected.map { RetrievalHit(it.chunk, it.score) }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var inter = 0
        val (small, big) = if (a.size < b.size) a to b else b to a
        for (x in small) if (x in big) inter++
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }
}

data class RetrievalHit(
    val chunk: IndexedChunk,
    val score: Double,
)
