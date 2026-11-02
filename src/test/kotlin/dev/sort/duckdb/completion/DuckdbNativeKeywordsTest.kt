package dev.sort.duckdb.completion

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class DuckdbNativeKeywordsTest {
    private fun prefix(sql: String) = requireNotNull(NativeKeywordPrefix.capture(sql, sql.length))
    @Test fun `only current statement up to caret is sent as escaped data`() {
        val text = "SELECT 1; SELECT '😀' AS x |> WH; PRIVATE_SUFFIX"
        val caret = text.indexOf("WH") + 2
        val p = requireNotNull(NativeKeywordPrefix.capture(text, caret))
        assertEquals(" SELECT '😀' AS x |> WH", p.sql)
        assertFalse(DuckdbNativeKeywords.sql(p.sql).contains("PRIVATE_SUFFIX"))
        assertFalse(DuckdbNativeKeywords.sql(p.sql).contains("brikk_completion_probe"))
        assertTrue(DuckdbNativeKeywords.sql(p.sql).contains("''😀''"))
        assertTrue(DuckdbNativeKeywords.sql(p.sql).contains("max_file_suggestion_count := 0"))
    }
    @Test fun `byte starts map emoji and CRLF without treating bytes as editor offsets`() {
        val p = prefix("SELECT '😀' AS x |> WH")
        assertEquals(20, p.wordStart)
        assertEquals(22, p.byteStart)
        val crlf = prefix("SELECT '😀' AS x\r\n|> WH")
        assertEquals(21, crlf.wordStart)
        assertEquals(23, crlf.byteStart)
    }
    @Test fun `comments strings unicode space normalization and sentinels are declined`() {
        for (sql in listOf("SELECT 'WH", "SELECT 1 -- WH", "/* WH", "SELECT \"WH", "SELECT 1\u00a0|> WH", "SELECT 1\u2003|> WH", "SELECT IntellijIdeaRulezzz")) {
            assertNull(sql, NativeKeywordPrefix.capture(sql, sql.length))
        }
    }
    @Test fun `missing function and foreign catalogs never invoke autocomplete`() {
        val calls = mutableListOf<String>()
        val missing = DuckdbNativeKeywords.query(prefix("SELECT WH")) { sql, _ -> calls += sql; emptyList() }
        assertTrue(missing.status.orEmpty().contains("LOAD autocomplete"))
        assertEquals(listOf(DuckdbNativeKeywords.FOREIGN_CATALOGS, DuckdbNativeKeywords.REGISTERED), calls)
        calls.clear()
        val foreign = DuckdbNativeKeywords.query(prefix("SELECT WH")) { sql, _ -> calls += sql; listOf(mapOf("type" to "postgres")) }
        assertTrue(foreign.status.orEmpty().contains("foreign"))
        assertEquals(listOf(DuckdbNativeKeywords.FOREIGN_CATALOGS), calls)
    }
    @Test fun `protocol only accepts keyword rows at the verified byte boundary and deduplicates`() {
        val p = prefix("SELECT '😀' AS x |> WH")
        val keyword = mapOf("suggestion" to "WHERE ", "extra_char" to " ", "suggestion_type" to "keyword", "suggestion_start" to "22", "suggestion_score" to "0")
        val result = DuckdbNativeKeywords.query(p) { sql, _ -> when (sql) {
            DuckdbNativeKeywords.FOREIGN_CATALOGS -> emptyList()
            DuckdbNativeKeywords.REGISTERED -> listOf(mapOf("extension_name" to "autocomplete", "parameters" to "[\"sql\",\"max_suggestion_count\",\"max_file_suggestion_count\",\"max_exact_suggestion_count\"]", "parameter_types" to "[\"VARCHAR\",\"UBIGINT\",\"UBIGINT\",\"UBIGINT\"]", "varargs" to null))
            else -> listOf(keyword, keyword, keyword + ("suggestion_type" to "column"), keyword + ("suggestion_start" to "20"))
        } }
        assertEquals(listOf(NativeKeyword("WHERE", ' ', 0)), result.items)
        assertThrows(CancellationException::class.java) { DuckdbNativeKeywords.query(p) { _, _ -> throw CancellationException() } }
    }
    @Test fun `insertion preserves suffix avoids doubled separators and refuses stale edits`() {
        val sql = "SELECT '😀' AS x |> WH suffix"
        val caret = sql.indexOf("WH") + 2
        val p = requireNotNull(NativeKeywordPrefix.capture(sql, caret))
        val insert = NativeKeywordInsertion(sql, p, "WHERE", ' ')
        val normal = sql.replace("WH suffix", "WHERE suffix")
        assertEquals(normal to (normal.indexOf("suffix")), insert.apply(normal, true))
        assertEquals(sql to caret, insert.apply(normal, false))
        assertNull(insert.apply(normal + " changed", true))
        val tight = "FROM t |> WHsuffix"
        val q = requireNotNull(NativeKeywordPrefix.capture(tight, tight.indexOf("WH") + 2))
        val tab = NativeKeywordInsertion(tight, q, "WHERE", ' ')
        assertEquals("FROM t |> WHERE suffix", tab.apply("FROM t |> WHERE", true)?.first)
        val complete = "FROM t |> WHERE i > 0"
        val middle = complete.indexOf("WHERE") + 2
        val inside = NativeKeywordInsertion(complete, requireNotNull(NativeKeywordPrefix.capture(complete, middle)), "WHERE", ' ')
        assertEquals(complete, inside.apply("FROM t |> WHEREERE i > 0", true)?.first)
        assertEquals(complete, inside.apply(complete, true)?.first)
        val blank = complete.indexOf("WHERE")
        val ahead = NativeKeywordInsertion(complete, requireNotNull(NativeKeywordPrefix.capture(complete, blank)), "WHERE", ' ')
        assertEquals(complete, ahead.apply("FROM t |> WHEREWHERE i > 0", true)?.first)
    }
}
