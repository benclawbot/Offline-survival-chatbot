package com.localchat.data.retrieval

import java.text.BreakIterator
import java.util.Locale

/**
 * Sentence-aware chunker. Walks the source text with the platform [BreakIterator]
 * (available on both JVM unit tests and Android via `java.text`), then packs whole
 * sentences into chunks of ~targetTokens with ~10% overlap. This avoids the
 * "fixed-window mid-sentence cut" failure mode, where a fact straddling a window
 * boundary was either duplicated as garbage or lost entirely.
 *
 * Token approximation: 4 chars/token. Adequate for budgeting; we never use it for
 * exact LLM-side accounting (engine has its own tokenizer).
 */
internal object SentenceChunker {

    fun chunk(
        text: String,
        targetTokens: Int = 350,
        overlapTokens: Int = 35,
        locale: Locale = Locale.ROOT,
    ): List<String> {
        val clean = text.replace('\u0000', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (clean.isEmpty()) return emptyList()

        val sentences = splitSentences(clean, locale).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return emptyList()

        val targetChars = targetTokens * 4
        val overlapChars = overlapTokens * 4
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        val carry = ArrayDeque<String>()
        var carryChars = 0

        fun flushCurrent() {
            val s = current.toString().trim()
            if (s.isNotEmpty()) chunks.add(s)
            // Build overlap from the tail sentences of the just-emitted chunk.
            current.clear()
            for (sent in carry) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sent)
            }
            carry.clear()
            carryChars = 0
        }

        // A single "sentence" can exceed targetChars (e.g. a PDF page with no sentence
        // breaks). Hard-split those so no chunk blows the budget and starves other docs.
        val units = sentences.flatMap { if (it.length > targetChars) hardSplit(it, targetChars) else listOf(it) }
        for (sentence in units) {
            val sLen = sentence.length
            if (current.isNotEmpty() && current.length + 1 + sLen > targetChars) {
                flushCurrent()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
            // Maintain a rolling tail of recent sentences for the next chunk's overlap.
            carry.addLast(sentence)
            carryChars += sLen + 1
            while (carryChars > overlapChars && carry.size > 1) {
                val dropped = carry.removeFirst()
                carryChars -= dropped.length + 1
            }
        }
        if (current.isNotEmpty()) {
            val s = current.toString().trim()
            if (s.isNotEmpty()) chunks.add(s)
        }
        return chunks
    }

    /** Split an over-long run on word boundaries into <= maxChars pieces (char-split a giant token). */
    private fun hardSplit(text: String, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (word in text.split(' ')) {
            when {
                word.length > maxChars -> {
                    if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                    var i = 0
                    while (i < word.length) {
                        out.add(word.substring(i, minOf(i + maxChars, word.length)))
                        i += maxChars
                    }
                }
                sb.length + 1 + word.length > maxChars -> { out.add(sb.toString()); sb.clear(); sb.append(word) }
                else -> { if (sb.isNotEmpty()) sb.append(' '); sb.append(word) }
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun splitSentences(text: String, locale: Locale): List<String> {
        val it = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val s = text.substring(start, end).trim()
            if (s.isNotEmpty()) out.add(s)
            start = end
            end = it.next()
        }
        return out
    }
}
