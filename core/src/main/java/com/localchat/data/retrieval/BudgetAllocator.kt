package com.localchat.data.retrieval

/**
 * Token-budget allocator. Given a pool of ranked chunks and a hard token budget,
 * decides which chunks survive into the LLM context window.
 *
 * Algorithm (fair-share with slack redistribution):
 *
 *  1. Group ranked chunks by their parent doc.
 *  2. Compute each doc's score (sum of its top chunks' RRF score).
 *  3. Reserve a per-doc minimum floor F so that no relevant doc is starved.
 *  4. Distribute the remaining budget proportionally to doc score.
 *  5. Within each doc, take chunks in rank order until the doc-budget is full.
 *  6. If a doc consumes less than its share, harvest the slack and redistribute
 *     to the next-most-relevant unsatisfied doc.
 *
 * This is the production-grade alternative to "take top N chunks": it stops a
 * single highly-ranked doc from monopolising the window while keeping a long
 * tail of weakly-relevant docs from drowning out the strong matches.
 */
internal object BudgetAllocator {

    /** Minimum tokens reserved per included doc. Below this it's not worth including. */
    const val PER_DOC_FLOOR = 200

    fun select(
        ranked: List<RankedChunk>,
        totalBudgetTokens: Int,
    ): List<RankedChunk> {
        if (ranked.isEmpty() || totalBudgetTokens <= 0) return emptyList()

        val byDoc = ranked.groupBy { it.chunk.docId }
            .mapValues { it.value.sortedByDescending { c -> c.score } }
        val docOrder = byDoc.entries
            .map { (docId, chunks) -> docId to chunks.sumOf { it.score } }
            .sortedByDescending { it.second }
            .map { it.first }
        val perDocAlloc = HashMap<String, Int>()

        // Step 1: floor allocation, only for docs that fit.
        var remaining = totalBudgetTokens
        val floor = PER_DOC_FLOOR.coerceAtMost(totalBudgetTokens)
        for (docId in docOrder) {
            if (remaining < floor) break
            perDocAlloc[docId] = floor
            remaining -= floor
        }

        // Step 2: proportional redistribution to docs that got a floor.
        val active = perDocAlloc.keys.toMutableSet()
        val activeScore = byDoc.entries
            .filter { it.key in active }
            .associate { e -> e.key to e.value.sumOf { it.score }.coerceAtLeast(1e-6) }
        val totalScore = activeScore.values.sum()
        if (totalScore > 0) {
            for (docId in docOrder) {
                if (docId !in active) continue
                val score = activeScore[docId] ?: continue
                val share = (remaining * (score / totalScore)).toInt()
                perDocAlloc[docId] = (perDocAlloc[docId] ?: 0) + share
                remaining -= share
            }
        }
        // Anything left over (rounding crumbs) drops onto the top doc.
        if (remaining > 0 && docOrder.isNotEmpty()) {
            val top = docOrder.first()
            if (top in active) perDocAlloc[top] = (perDocAlloc[top] ?: 0) + remaining
        }

        // Step 3: pick chunks per-doc up to its allocation; collect slack as we go.
        val picked = ArrayList<RankedChunk>()
        var slack = 0
        for (docId in docOrder) {
            val cap = perDocAlloc[docId] ?: continue
            var used = 0
            for (chunk in byDoc[docId].orEmpty()) {
                val cost = chunk.chunk.estimatedTokens
                if (used + cost > cap) break
                picked.add(chunk)
                used += cost
            }
            slack += (cap - used).coerceAtLeast(0)
        }

        // Step 4: redistribute slack to the next chunk that fits, in global rank order.
        if (slack > 0) {
            val pickedIds = picked.map { it.chunk.id }.toHashSet()
            for (chunk in ranked) {
                if (chunk.chunk.id in pickedIds) continue
                val cost = chunk.chunk.estimatedTokens
                if (cost <= slack) {
                    picked.add(chunk)
                    pickedIds.add(chunk.chunk.id)
                    slack -= cost
                }
                if (slack <= 0) break
            }
        }

        return picked.sortedByDescending { it.score }
    }
}

internal data class RankedChunk(val chunk: IndexedChunk, val score: Double)

