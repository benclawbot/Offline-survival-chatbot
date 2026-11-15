package com.localchat.data.retrieval

/**
 * Pure-Kotlin tokenizer. Lowercases, strips diacritics, tokenizes into letter+digit
 * runs, and produces both word-tokens and character-trigrams for the corresponding
 * indexes. Lives in JVM-compatible code (no Android classes) so the harness can
 * stress it directly.
 *
 * Why two streams from one pass: the BM25+ scorer wants whole words; the trigram
 * Jaccard scorer wants overlapping 3-grams. Doing both in one walk avoids a
 * second pass over the corpus on each query.
 */
internal object Tokenizer {

    /** Words ≥ 2 chars; supports any Unicode letter/digit (Latin, Cyrillic, CJK, …). */
    private val WORD_RX = Regex("[\\p{L}\\p{N}]{2,}")

    /** Stopwords kept tiny — over-stripping kills BM25 recall on short chat queries. */
    private val STOP = setOf(
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
        "you", "your", "have", "has", "had",
        "как", "что", "это", "для", "или", "если", "при", "про", "над", "под", "где", "чем",
        "не", "ли", "же",
    )

    fun normalize(s: String): String =
        // NFD splits accented letters into base + combining mark; we then strip the marks.
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()

    fun words(text: String): List<String> {
        val n = normalize(text)
        return WORD_RX.findAll(n).map { it.value }.filterNot { it in STOP }.toList()
    }

    /**
     * Character trigrams. Inserts a single space at word boundaries so that
     * trigrams stay within words (avoids "x_y" cross-word artefacts dominating
     * the index, which dilutes Jaccard quality).
     */
    fun trigrams(text: String): List<String> {
        val n = normalize(text)
        val joined = WORD_RX.findAll(n).joinToString(" ") { it.value }
        if (joined.length < 3) return emptyList()
        val out = ArrayList<String>(joined.length)
        for (i in 0..joined.length - 3) {
            val tg = joined.substring(i, i + 3)
            if (!tg.contains(' ')) out.add(tg)
        }
        return out
    }
}
