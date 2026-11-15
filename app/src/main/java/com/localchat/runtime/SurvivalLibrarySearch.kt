package com.localchat.runtime

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/** BM25 lookup into Survival LLM's FTS5 index, with passage bytes read from its text shards. */
@Singleton
class SurvivalLibrarySearch @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    data class Hit(val title: String, val source: String, val url: String, val license: String, val text: String)

    fun search(query: String, limit: Int = 4): List<Hit> {
        val root = File(context.filesDir, "survival/Library")
        val dbFile = File(root, "index.sqlite")
        if (!dbFile.isFile) return emptyList()
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }.distinct().take(12)
        if (terms.isEmpty()) return emptyList()
        val matchAll = terms.joinToString(" ") { "\"${it.replace("\"", "")}\"" }
        val matchAny = terms.joinToString(" OR ") { "\"${it.replace("\"", "")}\"" }
        val database = BundledSQLiteDriver().open(dbFile.path)
        try {
            val results = mutableListOf<Hit>()
            val matchingIds = runCatching {
                database.prepare("SELECT rowid,rank FROM fts WHERE fts MATCH ? AND rank MATCH 'bm25(4.0, 1.0)' ORDER BY rank LIMIT 300").use { cursor ->
                    cursor.bindText(1, matchAll)
                    val ids = mutableListOf<Long>()
                    while (cursor.step()) ids += cursor.getLong(0)
                    ids
                }
            }.getOrDefault(emptyList()).let { first ->
                if (first.size >= 3 || terms.size == 1) first else
                    database.prepare("SELECT rowid,rank FROM fts WHERE fts MATCH ? AND rank MATCH 'bm25(4.0, 1.0)' ORDER BY rank LIMIT 300").use { cursor ->
                        cursor.bindText(1, matchAny)
                        val ids = mutableListOf<Long>()
                        while (cursor.step()) ids += cursor.getLong(0)
                        ids
                    }
            }
            if (matchingIds.isEmpty()) return emptyList()
            val selected = matchingIds.joinToString(",")
            val sourceRows = mutableMapOf<Long, Array<String?>>()
            database.prepare("""SELECT p.id,d.title,s.label,d.url,s.license,f.path,p.offset,p.length
                FROM passages p JOIN docs d ON d.id=p.doc_id JOIN sources s ON s.id=d.source_id
                JOIN files f ON f.id=d.file_id WHERE p.id IN ($selected)""".trimIndent()).use { cursor ->
                while (cursor.step()) sourceRows[cursor.getLong(0)] = Array(7) { i -> if (cursor.isNull(i + 1)) null else cursor.getText(i + 1) }
            }
            for (passageId in matchingIds) {
                val row = sourceRows[passageId] ?: continue
                val title = row[0].orEmpty()
                if (results.any { it.title == title }) continue
                val source = row[1].orEmpty()
                val url = row[2].orEmpty()
                val license = row[3].orEmpty()
                val path = row[4].orEmpty()
                val offset = row[5]?.toLongOrNull() ?: continue
                val length = (row[6]?.toLongOrNull() ?: 0L).coerceIn(0, 24_000)
                if (length == 0L) continue
                val shard = File(root, path).canonicalFile
                if (!shard.path.startsWith(root.canonicalPath + File.separator) || !shard.isFile) continue
                val bytes = ByteArray(length.toInt())
                val text = RandomAccessFile(shard, "r").use { file ->
                    if (offset >= file.length()) return@use ""
                    file.seek(offset)
                    val n = file.read(bytes)
                    if (n <= 0) "" else String(bytes, 0, n, StandardCharsets.UTF_8).trim()
                }
                if (text.isNotBlank()) results += Hit(title, source, url, license, text)
                if (results.size >= limit.coerceIn(1, 8)) break
            }
            return results
        } finally {
            database.close()
        }
    }
}
