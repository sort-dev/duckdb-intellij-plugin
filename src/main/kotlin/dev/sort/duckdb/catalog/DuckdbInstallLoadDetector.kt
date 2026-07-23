package dev.sort.duckdb.catalog

/**
 * Cheap statement-head scan for executed SQL that changes the function inventory: `INSTALL`,
 * `FORCE INSTALL`, or `LOAD` as the first meaningful word(s) of any statement in the script.
 *
 * Deliberately NOT a parse (this runs on every observed execution): the text is split on `;`
 * ("statement-ish" — a `;` inside a string literal over-splits, which can only produce a spare
 * debounced re-harvest, never a miss), leading whitespace and `--`/`/* */` comments are skipped,
 * and only the statement HEAD is matched with word-boundary discipline — `INSTALL` inside a
 * string literal, an identifier (`install_log`), or a longer word (`INSTALLER`) never matches.
 *
 * `UPDATE EXTENSIONS` intentionally does NOT match: it refreshes the extension repository
 * metadata but loads nothing, so the live function inventory is unchanged.
 */
object DuckdbInstallLoadDetector {

    /** True when [script] contains a statement whose head is INSTALL / FORCE INSTALL / LOAD. */
    fun triggersCatalogChange(script: String?): Boolean {
        if (script.isNullOrBlank()) return false
        return script.split(';').any { statementTriggers(it) }
    }

    private fun statementTriggers(statement: String): Boolean {
        val head = firstMeaningfulWord(statement, 0) ?: return false
        return when {
            head.word.equals("INSTALL", ignoreCase = true) -> true
            head.word.equals("LOAD", ignoreCase = true) -> true
            head.word.equals("FORCE", ignoreCase = true) ->
                firstMeaningfulWord(statement, head.end)?.word.equals("INSTALL", ignoreCase = true)
            else -> false
        }
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
