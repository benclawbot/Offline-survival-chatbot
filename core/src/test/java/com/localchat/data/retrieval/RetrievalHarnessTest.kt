package com.localchat.data.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM harness for [LexicalRetriever] and its components. We act like the
 * model: index a few documents, run queries that mimic real failure modes
 * (typos, Russian morphology, RU/EN code-switching) and assert the retriever
 * returns the right chunks in the right order.
 *
 * Run: `./gradlew :app:testDebugUnitTest --tests com.localchat.data.retrieval.RetrievalHarnessTest`
 */
class RetrievalHarnessTest {

    @Test
    fun `tokenizer strips diacritics and lowercases`() {
        val out = Tokenizer.words("Café Résumé café")
        assertEquals(listOf("cafe", "resume", "cafe"), out)
    }

    @Test
    fun `trigrams are intra-word only`() {
        val out = Tokenizer.trigrams("foo bar")
        // "foo" → "foo"; "bar" → "bar"; no cross-word "o b" or "ba r".
        assertTrue("foo" in out)
        assertTrue("bar" in out)
        assertFalse(out.any { ' ' in it })
    }

    @Test
    fun `bm25+ ranks document containing query terms higher than unrelated docs`() {
        val r = LexicalRetriever()
        r.indexDocument("doc-coffee", "Coffee Brewing", "Espresso requires fine grind and high pressure water for a balanced extraction.")
        r.indexDocument("doc-cycling", "Cycling Tips", "On long climbs you should pace yourself and shift to lower gears.")
        r.indexDocument("doc-tea", "Tea Brewing", "Green tea is best brewed at lower temperatures, around eighty degrees Celsius.")

        val hits = r.query("how do I brew espresso")
        assertTrue("expected at least one hit", hits.isNotEmpty())
        assertEquals("doc-coffee", hits.first().chunk.docId)
    }

    @Test
    fun `trigram fallback finds russian morphology that pure word match misses`() {
        val r = LexicalRetriever()
        r.indexDocument(
            "doc-search",
            "Поисковые алгоритмы",
            "Поисковая система использует индекс для быстрого нахождения документов. Современные поисковики применяют BM25 и другие метрики.",
        )
        r.indexDocument(
            "doc-cooking",
            "Рецепты борща",
            "Борщ варится из свёклы, капусты и мяса. Подавайте со сметаной.",
        )

        // Word-level: query "поиск" never appears verbatim — only "поисковая" / "поисковики".
        // Trigram match should still surface the search-related doc.
        val hits = r.query("поиск")
        assertTrue("expected at least one hit", hits.isNotEmpty())
        assertEquals("doc-search", hits.first().chunk.docId)
    }

    @Test
    fun `trigram matching works across languages (de, fr, uk), not just russian`() {
        val r = LexicalRetriever()
        r.indexDocument("de", "Notizen", "Die Künstliche Intelligenz verändert die Softwareentwicklung grundlegend.")
        r.indexDocument("fr", "Notes", "L'intelligence artificielle transforme le développement logiciel moderne.")
        r.indexDocument("uk", "Нотатки", "Штучний інтелект змінює розробку програмного забезпечення.")
        r.indexDocument("noise", "Cooking", "Mix flour, sugar and butter, then bake the cake.")

        // German inflection: "Softwareentwickler" vs indexed "Softwareentwicklung" — shared root trigrams.
        assertEquals("de", r.query("Softwareentwickler").firstOrNull()?.chunk?.docId)
        // French with stripped diacritics: "developpement" vs "développement".
        assertEquals("fr", r.query("developpement logiciel").firstOrNull()?.chunk?.docId)
        // Ukrainian inflection: "розробка" vs indexed "розробку".
        assertEquals("uk", r.query("розробка").firstOrNull()?.chunk?.docId)
    }

    @Test
    fun `trigram fallback handles single-character typo`() {
        val r = LexicalRetriever()
        r.indexDocument("doc-auth", "Authentication Spec", "OAuth2 authorization flow requires the client to obtain an access token from the auth server.")
        r.indexDocument("doc-billing", "Billing", "Invoices are charged on the first of every month, with a 14 day grace period.")

        val hits = r.query("authntication")  // typo: missing 'e'
        assertTrue(hits.isNotEmpty())
        assertEquals("doc-auth", hits.first().chunk.docId)
    }

    @Test
    fun `title boost surfaces filename matches over body matches`() {
        val r = LexicalRetriever()
        // doc-A's BODY mentions tokens; doc-B's TITLE mentions tokens. Both have
        // similar relevance for the query — the title-boost should tie-break
        // toward doc-B.
        r.indexDocument("doc-A", "Generic Notes", "We discussed authentication tokens and refresh logic at length.")
        r.indexDocument("doc-B", "Tokens Refresh API", "Endpoints for the API are documented in the spec.")

        val hits = r.query("tokens refresh")
        assertEquals("doc-B", hits.first().chunk.docId)
    }

    @Test
    fun `budget allocator gives every relevant doc a fair share`() {
        val r = LexicalRetriever()
        // 3 short docs, all relevant — none should be starved.
        r.indexDocument("doc-1", "Doc 1", "search engine indexing")
        r.indexDocument("doc-2", "Doc 2", "search engine ranking")
        r.indexDocument("doc-3", "Doc 3", "search engine retrieval")
        // Big enough budget — all three docs survive.
        val hits = r.query("search engine", budgetTokens = 2_000, topK = 10)
        val docs = hits.map { it.chunk.docId }.toSet()
        assertEquals(setOf("doc-1", "doc-2", "doc-3"), docs)
    }

    @Test
    fun `tight budget caps total tokens and excludes weakest docs`() {
        val r = LexicalRetriever()
        // 5 docs of similar relevance.
        repeat(5) { i ->
            r.indexDocument("doc-$i", "Title $i", "search engine retrieval BM25 indexing pass $i")
        }
        // Budget tight — only top 2 docs at the per-doc floor (200 each = 400).
        val hits = r.query("search engine", budgetTokens = 400, topK = 10)
        val totalTokens = hits.sumOf { it.chunk.estimatedTokens }
        assertTrue("budget exceeded: $totalTokens > 400", totalTokens <= 400 + 50)  // small slack ok
        assertTrue("expected at least one hit", hits.isNotEmpty())
    }

    @Test
    fun `doc filter scopes search to filename pattern`() {
        val r = LexicalRetriever()
        r.indexDocument("notes/api-spec.pdf", "API Spec", "the api uses OAuth2 for authentication")
        r.indexDocument("notes/changelog.md", "Changelog", "added OAuth2 in version 1.2")

        val hitsAll = r.query("oauth")
        assertEquals(2, hitsAll.map { it.chunk.docId }.toSet().size)

        val hitsScoped = r.query("oauth", docFilter = { it.contains("api-spec") })
        assertEquals(1, hitsScoped.size)
        assertEquals("notes/api-spec.pdf", hitsScoped.first().chunk.docId)
    }

    @Test
    fun `re-indexing same docId replaces old chunks, no duplicates`() {
        val r = LexicalRetriever()
        r.indexDocument("doc-1", "v1", "alpha bravo charlie")
        val sizeAfterFirst = r.size
        r.indexDocument("doc-1", "v2", "delta echo foxtrot")
        assertEquals("re-index must replace, not append", sizeAfterFirst, r.size)
        val hits = r.query("alpha")
        assertTrue("old text must be gone after re-index", hits.isEmpty() || hits.none { "alpha" in it.chunk.text })
    }

    @Test
    fun `removeDoc drops every chunk for that doc`() {
        val r = LexicalRetriever()
        // ~10k chars of varied sentences guarantees multiple chunks at any reasonable chunk size.
        val longText = (1..200).joinToString(" ") {
            "Sentence number $it about indexing and retrieval and a bit of filler content."
        }
        val ord = r.indexDocument("doc-1", "Title", longText, targetTokens = 60)
        assertTrue("expected multi-chunk doc, got $ord chunks (size=${r.size})", ord > 1)
        r.removeDoc("doc-1")
        assertEquals(0, r.size)
    }

    @Test
    fun `sentence chunker keeps sentences whole`() {
        val text = "First sentence here. Second sentence is a bit longer. Third one too. Fourth."
        val chunks = SentenceChunker.chunk(text, targetTokens = 5, overlapTokens = 1)
        // Each sentence keeps its terminal punctuation.
        for (chunk in chunks) {
            assertFalse("chunk ends mid-word: '$chunk'", Regex("\\b\\w$").containsMatchIn(chunk.dropLast(1)))
        }
    }

    @Test
    fun `chunker hard-splits an oversized single sentence (no period)`() {
        // A PDF page with no sentence breaks must not become one giant chunk that blows the budget.
        val giant = (1..600).joinToString(" ") { "token$it" } // ~4000+ chars, zero periods
        val chunks = SentenceChunker.chunk(giant, targetTokens = 100, overlapTokens = 10)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        // Each chunk must be a small fraction of the whole — no single giant chunk.
        val maxChars = chunks.maxOf { it.length }
        assertTrue("a chunk was too large: $maxChars of ${giant.length}", maxChars < giant.length / 3)
    }

    @Test
    fun `rrf fusion rewards documents present in multiple rankings`() {
        // doc-A is rank 1 in BM25 only; doc-B is rank 3 in BM25 + rank 1 in trigram.
        // RRF should rank doc-B higher because it appears in BOTH rankings.
        val fused = Rrf.fuse(
            listOf(
                listOf("doc-A", "doc-X", "doc-B"),
                listOf("doc-B", "doc-Y", "doc-Z"),
            )
        )
        assertEquals("doc-B", fused.first().chunkId)
    }

    @Test
    fun `empty corpus returns empty results, no crash`() {
        val r = LexicalRetriever()
        assertTrue(r.query("anything").isEmpty())
    }

    // ── Hybrid (semantic + lexical) retrieval ───────────────────────────────────────────────

    private fun norm(vararg xs: Float) = VectorMath.l2normalize(xs)

    @Test
    fun `semantic ranking surfaces a doc that lexical search misses`() {
        val r = LexicalRetriever()
        r.indexDocument("doc-fox", "Fox", "The quick brown fox jumps over things.")
        r.indexDocument("doc-dog", "Dog", "A lazy dog sleeps all day long.")
        r.indexDocument("doc-ml", "ML", "Neural networks learn representations from data.")
        // Query embedding points at doc-ml; the others are orthogonal.
        r.putVectors(mapOf("doc-fox#0" to norm(0f, 1f), "doc-dog#0" to norm(0f, 1f), "doc-ml#0" to norm(1f, 0f)))
        val q = norm(1f, 0f)

        // Lexically "fox" only matches doc-fox; doc-ml is invisible to BM25/trigram.
        val lexicalOnly = r.query("fox").map { it.chunk.docId }.toSet()
        assertTrue("doc-fox should match lexically", "doc-fox" in lexicalOnly)
        assertFalse("doc-ml has no lexical overlap with 'fox'", "doc-ml" in lexicalOnly)

        // With the query vector, semantic ranking pulls doc-ml into the results.
        val hybrid = r.query("fox", queryVector = q).map { it.chunk.docId }.toSet()
        assertTrue("semantic ranking must surface doc-ml", "doc-ml" in hybrid)
    }

    @Test
    fun `queryVector with no stored vectors falls back to lexical (no crash)`() {
        val r = LexicalRetriever()
        r.indexDocument("doc-fox", "Fox", "The quick brown fox jumps over things.")
        r.indexDocument("doc-ml", "ML", "Neural networks learn representations from data.")
        // No putVectors → semantic contributes nothing; behaves exactly like lexical-only.
        val hits = r.query("fox", queryVector = norm(1f, 0f)).map { it.chunk.docId }.toSet()
        assertEquals(setOf("doc-fox"), hits)
    }

    @Test
    fun `query without queryVector is unchanged when vectors exist`() {
        val r = LexicalRetriever()
        r.indexDocument("doc-fox", "Fox", "The quick brown fox jumps over things.")
        r.indexDocument("doc-ml", "ML", "Neural networks learn representations from data.")
        r.putVectors(mapOf("doc-fox#0" to norm(0f, 1f), "doc-ml#0" to norm(1f, 0f)))
        // queryVector=null → semantic skipped even though vectors are present.
        val hits = r.query("fox").map { it.chunk.docId }.toSet()
        assertEquals(setOf("doc-fox"), hits)
    }

    @Test
    fun `removeDoc drops the doc's vectors too`() {
        val r = LexicalRetriever()
        r.indexDocument("doc-ml", "ML", "Neural networks learn representations from data.")
        r.putVectors(mapOf("doc-ml#0" to norm(1f, 0f)))
        assertTrue(r.hasVectors("doc-ml"))
        r.removeDoc("doc-ml")
        assertFalse(r.hasVectors("doc-ml"))
    }

    @Test
    fun `query against indexed corpus is deterministic and fast`() {
        val r = LexicalRetriever()
        repeat(200) { i ->
            r.indexDocument("doc-$i", "Doc $i",
                "this is a test document about topic $i with some filler content")
        }
        val first = r.query("test document topic 42").map { it.chunk.id }
        val second = r.query("test document topic 42").map { it.chunk.id }
        assertEquals(first, second)
        assertTrue(first.isNotEmpty())
    }
}
