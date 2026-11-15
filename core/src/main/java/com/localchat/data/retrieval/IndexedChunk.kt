package com.localchat.data.retrieval

/**
 * One unit of indexed text. Documents (PDF/HTML/web page) are split into many
 * [IndexedChunk]s; the retriever ranks chunks, then groups them by [docId] in
 * the budget allocator.
 *
 * [tokens] and [trigrams] are pre-computed at index time so that scoring at
 * query time is a tight inner loop with no normalisation work. [titleTokenSet]
 * holds tokens drawn from the parent doc's title/filename so that the BM25+
 * scorer can apply a title-boost without extra string parsing.
 */
data class IndexedChunk(
    val id: String,
    val docId: String,
    /** Display name of the parent doc — shown in the UI; not used for scoring. */
    val docTitle: String,
    /** Optional in-document section heading (PDF outline, HTML <h2>, etc.). */
    val sectionHeading: String? = null,
    /** Position of this chunk in its document (0-based). */
    val ordinal: Int,
    /** Raw chunk text — what gets fed to the LLM. */
    val text: String,
    /** Pre-tokenised words (lowercased, diacritics-stripped, no stopwords). */
    val tokens: List<String>,
    /** Pre-computed trigram set for Jaccard similarity. */
    val trigrams: Set<String>,
    /** Pre-aggregated term frequency for BM25+. */
    val termFreq: Map<String, Int>,
    /** Tokens drawn from doc title + filename + section heading; boosted in BM25+. */
    val titleTokenSet: Set<String>,
) {
    /** Approximate token count — used by the budget allocator. */
    val estimatedTokens: Int get() = (text.length + 3) / 4

    companion object {
        fun of(
            id: String,
            docId: String,
            docTitle: String,
            ordinal: Int,
            text: String,
            sectionHeading: String? = null,
        ): IndexedChunk {
            val tokens = Tokenizer.words(text)
            val titleSrc = listOfNotNull(docTitle, sectionHeading).joinToString(" ")
            val titleTokens = Tokenizer.words(titleSrc).toSet()
            // Trigrams cover BOTH body and title — that way a title-only match
            // still produces overlap with the query in the trigram ranker (and
            // RRF then rewards docs hit by both rankers).
            val trigrams = (Tokenizer.trigrams(text) + Tokenizer.trigrams(titleSrc)).toSet()
            val tf = HashMap<String, Int>(tokens.size.coerceAtMost(64))
            for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
            return IndexedChunk(
                id = id,
                docId = docId,
                docTitle = docTitle,
                sectionHeading = sectionHeading,
                ordinal = ordinal,
                text = text,
                tokens = tokens,
                trigrams = trigrams,
                termFreq = tf,
                titleTokenSet = titleTokens,
            )
        }
    }
}
