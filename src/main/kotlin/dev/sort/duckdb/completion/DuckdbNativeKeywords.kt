package dev.sort.duckdb.completion

import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.shape.SqlNameContext
import dev.sort.duckdb.grammar.DuckdbGrammarProfile

internal data class NativeKeyword(val word: String, val extra: Char?, val score: Long)
internal data class NativeKeywords(val items: List<NativeKeyword> = emptyList(), val status: String? = null)
internal data class NativeKeywordPrefix(val sql: String, val start: Int, val caret: Int, val wordStart: Int) {
    val typed get() = sql.substring(wordStart - start)
    val byteStart get() = sql.substring(0, wordStart - start).toByteArray(Charsets.UTF_8).size
    companion object {
        const val MAX_DOCUMENT = 100_000
        const val MAX_PREFIX = 16_384
        fun capture(text: String, caret: Int, checkCanceled: () -> Unit = {}): NativeKeywordPrefix? {
            if (text.length > MAX_DOCUMENT || caret !in 0..text.length || caret > 65_536) return null
            val before = text.substring(0, caret)
            if ('\u0000' in before || "IntellijIdeaRulezzz" in before) return null
            // DuckDB normalizes these before calculating byte offsets. Decline, rather than guess a span.
            if (before.any { it.code > 127 && (it.isWhitespace() || Character.isSpaceChar(it)) }) return null
            val range = SqlScriptSnapshot.parse(before, "duckdb", checkCanceled).at(caret)
            val start = range?.start ?: caret
            val sql = before.substring(start)
            val word = Regex("[A-Za-z_][A-Za-z0-9_]*$").find(sql)
            val localStart = word?.range?.first ?: sql.length
            if (sql.length > MAX_PREFIX || SqlNameContext.of(sql.substring(0, localStart), "duckdb", checkCanceled)?.inCode != true) return null
            if (sql.any { it.isSurrogate() } && !validSurrogates(sql)) return null
            return NativeKeywordPrefix(sql, start, caret, start + localStart)
        }
        private fun validSurrogates(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                if (text[i].isHighSurrogate()) {
                    if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return false
                    i++
                } else if (text[i].isLowSurrogate()) return false
                i++
            }
            return true
        }
    }
}

internal object DuckdbNativeKeywords {
    const val FOREIGN_CATALOGS = "SELECT type FROM system.main.duckdb_databases() WHERE type IS NULL OR type <> 'duckdb' LIMIT 1"
    const val REGISTERED = "SELECT function_name FROM system.main.duckdb_functions() WHERE database_name = 'system' AND schema_name = 'main' AND function_name = 'sql_auto_complete' AND function_type = 'table' LIMIT 1"
    const val LIMIT = 64
    fun sql(prefix: String) = "SELECT suggestion, suggestion_start, suggestion_type, suggestion_score, extra_char " +
        "FROM system.main.sql_auto_complete(${DuckdbGrammarProfile.literal(prefix)}, max_file_suggestion_count := 0, " +
        "max_suggestion_count := $LIMIT, max_exact_suggestion_count := $LIMIT) " +
        "WHERE suggestion_type = 'keyword' ORDER BY suggestion_score, suggestion LIMIT $LIMIT"

    fun query(prefix: NativeKeywordPrefix, rows: (String, Int) -> List<Map<String, Any?>>): NativeKeywords {
        if (rows(FOREIGN_CATALOGS, 1).isNotEmpty()) return NativeKeywords(status = "Native keywords unavailable with foreign attached catalogs; existing completion remains available.")
        // Never invoke a missing known-extension function: DuckDB could otherwise auto-install/load it.
        if (rows(REGISTERED, 1).isEmpty()) return NativeKeywords(status = "Native keywords need LOAD autocomplete in this data source. Install it only by an explicit user action.")
        val items = rows(sql(prefix.sql), LIMIT).mapNotNull { row ->
            if (row["suggestion_type"]?.toString() != "keyword" || row["suggestion_start"]?.toString()?.toIntOrNull() != prefix.byteStart) return@mapNotNull null
            val suggestion = row["suggestion"]?.toString() ?: return@mapNotNull null
            val extraText = row["extra_char"]?.toString()
            val extra = if (extraText == null || extraText.isEmpty()) null else extraText.singleOrNull() ?: return@mapNotNull null
            if (extra != null && extra !in " (") return@mapNotNull null
            if (extra != null && !suggestion.endsWith(extra)) return@mapNotNull null
            val word = if (extra == null) suggestion else suggestion.dropLast(1)
            if (!Regex("[A-Za-z_][A-Za-z0-9_]*(?: [A-Za-z_][A-Za-z0-9_]*)*").matches(word)) return@mapNotNull null
            NativeKeyword(word, extra, row["suggestion_score"]?.toString()?.toLongOrNull() ?: 0)
        }.distinctBy { it.word.uppercase() }
        return NativeKeywords(items)
    }
}
