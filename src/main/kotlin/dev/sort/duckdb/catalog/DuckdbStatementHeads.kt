package dev.sort.duckdb.catalog

/**
 * Cheap statement-head scanner shared by the execution observer's detectors
 * ([DuckdbInstallLoadDetector] for the function inventory, [DuckdbAttachDetector] for the object
 * tree).
 *
 * Deliberately NOT a parse (this runs on every observed execution in the project, any dbms): the
 * script is split on `;` ("statement-ish" — a `;` inside a string literal over-splits, which can
 * only produce a spare debounced refresh, never a miss), leading whitespace and `--` / block
 * comments are skipped, and only the statement HEAD words are read with word-boundary discipline —
 * a keyword inside a string literal, an identifier (`install_log`, `attach_count`), or a longer
 * word (`INSTALLER`, `ATTACHMENTS`) never matches.
 */
internal object DuckdbStatementHeads {

    /** Statement-ish slices of [script] (see the class note on `;`). */
    fun statements(script: String): List<String> = script.split(';')

    /**
     * The first [max] keyword-shaped words of [statement], skipping leading whitespace and
     * comments. Stops early at anything that is not a keyword head (`(`, a quote, a digit), so
     * `ATTACH 'db.duckdb' AS x` yields just `[ATTACH]` while `FORCE INSTALL x` yields
     * `[FORCE, INSTALL]`.
     */
    fun headWords(statement: String, max: Int): List<String> {
        val out = ArrayList<String>(max)
        var from = 0
        while (out.size < max) {
            val word = firstMeaningfulWord(statement, from) ?: break
            out.add(word.word)
            from = word.end
        }
        return out
    }

    private data class Word(val word: String, val end: Int)

    /** The first keyword-shaped token at/after [from], skipping whitespace and comments. */
    private fun firstMeaningfulWord(text: String, from: Int): Word? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && text.startsWith("--", i) -> {
                    val nl = text.indexOf('\n', i)
                    if (nl < 0) return null
                    i = nl + 1
                }
                c == '/' && text.startsWith("/*", i) -> {
                    val close = text.indexOf("*/", i + 2)
                    if (close < 0) return null
                    i = close + 2
                }
                isWordStart(c) -> {
                    var j = i
                    // Continuation includes digits: `install2` is ONE identifier, not INSTALL+2.
                    while (j < text.length && (isWordStart(text[j]) || text[j] in '0'..'9')) j++
                    return Word(text.substring(i, j), j)
                }
                else -> return null // anything else ('(', quote, digit-led, ...) is not a keyword head
            }
        }
        return null
    }

    private fun isWordStart(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
}
