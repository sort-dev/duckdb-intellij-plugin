/*
 * Portions of this file are adapted from StarRocks Support
 * (https://github.com/ycyz97/starrocks-datagrip-plugin), Copyright the StarRocks Support
 * contributors, licensed under the Apache License, Version 2.0, by way of our own
 * doris-intellij-plugin (github.com/sort-dev/doris-intellij-plugin). The statement-dispatch and
 * lenient-parsing approach (wordAt / statementContainsAny / lenient consume-to-';') derives from
 * that lineage, modified for DuckDB syntax. See THIRD_PARTY_NOTICES.md.
 */
package dev.sort.duckdb.sql

import com.intellij.lang.PsiBuilder
import com.intellij.sql.dialects.postgres.PgParser
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_STATEMENT

/**
 * DuckDB statement parsing on the PG foundation, Tier-1 (statement boundaries).
 *
 * When a statement leads with DuckDB-only syntax the PG grammar cannot represent (ATTACH,
 * SUMMARIZE, PIVOT/UNPIVOT, FROM-first queries, CREATE MACRO/SECRET, modern COPY options,
 * INSTALL/LOAD, EXPORT/IMPORT DATABASE, PRAGMA, ...), consume tokens to the next `;` and wrap
 * them in ONE SQL_STATEMENT node. That keeps statement-at-caret / run-block boundaries correct
 * everywhere; inner structure for these statements arrives with later stages (and real errors
 * with the Stage-3 PREPARE validator). Everything else falls through to super for full PG
 * structure + completion.
 *
 * Dispatch decisions are bounded look-aheads (≤ [MAX_LOOKAHEAD] tokens, always rolled back), and
 * gates are chosen to never steal statements PG parses fine: `COPY`/`SET` stay with super unless
 * a DuckDB-only marker is present; `CREATE` is only taken for MACRO/SECRET within the first few
 * words.
 */
class DuckdbPsiParser : PgParser(false) {

    override fun parseSqlStatement(builder: PsiBuilder, level: Int): Boolean {
        when (wordAt(builder, 0)) {
            // Statement heads PG has no rule for at all — always lenient.
            "ATTACH", "DETACH", "SUMMARIZE", "DESCRIBE", "PIVOT", "UNPIVOT",
            "INSTALL", "FORCE", "EXPORT", "PRAGMA", "USE",
            -> return parseLenientStatement(builder, SQL_STATEMENT)

            // LOAD/IMPORT/CALL/CHECKPOINT exist in this grammar family only as other things
            // (or not at all) — DuckDB's forms are statements: LOAD spatial; IMPORT DATABASE 'd';
            // CALL pragma_table_info('t'); [FORCE] CHECKPOINT;
            "LOAD", "IMPORT", "CALL", "CHECKPOINT",
            -> return parseLenientStatement(builder, SQL_STATEMENT)

            // FROM-first queries (`FROM t SELECT ... / FROM t;`) — PG statements never lead with
            // FROM, so this is unambiguous.
            "FROM" -> return parseLenientStatement(builder, SQL_STATEMENT)

            // CTAS in FROM-first form (CREATE TABLE x AS FROM ...), CREATE TYPE aliases PG lacks.
            // Then: CREATE [OR REPLACE] [TEMP|TEMPORARY|PERSISTENT] MACRO/SECRET — check the first few
            // words only, so CREATE TABLE t (macro INT) never loses its real parse. Longest
            // qualifier chain is 3 words (OR REPLACE TEMP), so MACRO/SECRET sits at word 1..4.
            "CREATE" -> {
                if (statementContainsWordThen(builder, "AS", "FROM")) {
                    return parseLenientStatement(builder, SQL_STATEMENT)
                }
                if (statementContainsWordThenToken(builder, "AS", "(")) {
                    // parenthesized CTAS body — the PG grammar here rejects it
                    return parseLenientStatement(builder, SQL_STATEMENT)
                }
                if (wordAt(builder, 1) == "TYPE" && !statementContainsAny(builder, "ENUM")) {
                    return parseLenientStatement(builder, SQL_STATEMENT)
                }
                for (i in 1..4) {
                    when (wordAt(builder, i)) {
                        "MACRO", "SECRET" -> return parseLenientStatement(builder, SQL_STATEMENT)
                        "OR", "REPLACE", "TEMP", "TEMPORARY", "PERSISTENT" -> continue
                        else -> break
                    }
                }
            }

            // COPY exists in PG; take it ONLY when a DuckDB-only option marker is present
            // (`FORMAT parquet`, partitioned writes, compression codecs) — plain PG COPY keeps
            // its structured parse.
            "COPY" -> {
                if (statementContainsAny(
                        builder,
                        "PARQUET", "PARTITION_BY", "COMPRESSION", "OVERWRITE_OR_IGNORE",
                        "FILE_SIZE_BYTES", "PER_THREAD_OUTPUT", "APPEND", "AUTO_DETECT",
                        "SAMPLE_SIZE", "ALL_VARCHAR", "IGNORE_ERRORS", "NULL_PADDING",
                        "NORMALIZE_NAMES", "FILENAME", "HIVE_PARTITIONING", "UNION_BY_NAME",
                        "DATEFORMAT", "TIMESTAMPFORMAT",
                    )
                ) {
                    return parseLenientStatement(builder, SQL_STATEMENT)
                }
            }

            // SET x = v parses as PG; DuckDB's scoped/variable/schema forms don't.
            "SET", "RESET" -> {
                when (wordAt(builder, 1)) {
                    "GLOBAL", "SESSION", "LOCAL", "VARIABLE", "SCHEMA" ->
                        return parseLenientStatement(builder, SQL_STATEMENT)
                }
            }

            // EXPLAIN of a DuckDB-only statement form.
            "EXPLAIN" -> {
                when (wordAt(builder, 1)) {
                    "PRAGMA", "FROM", "SUMMARIZE", "DESCRIBE", "PIVOT", "UNPIVOT", "ANALYZE" ->
                        return parseLenientStatement(builder, SQL_STATEMENT)
                }
            }

            // INSERT OR REPLACE/IGNORE INTO — PG has no OR-conflict-shorthand form.
            "INSERT" -> {
                if (wordAt(builder, 1) == "OR") return parseLenientStatement(builder, SQL_STATEMENT)
            }
        }
        return super.parseSqlStatement(builder, level)
    }

    // --- lenient machinery (adapted from doris-intellij / StarRocks lineage) ---

    private fun parseLenientStatement(builder: PsiBuilder, done: com.intellij.psi.tree.IElementType): Boolean {
        val marker = builder.mark()
        consumeToSemicolon(builder)
        marker.done(done)
        return true
    }

    private fun consumeToSemicolon(builder: PsiBuilder) {
        while (!builder.eof() && builder.tokenText != ";") builder.advanceLexer()
    }

    /** The uppercased Nth letter-leading token from the current position, or null. Non-consuming. */
    private fun wordAt(builder: PsiBuilder, offset: Int): String? {
        val marker = builder.mark()
        var current = 0
        var result: String? = null
        while (!builder.eof() && builder.tokenText != ";" && current <= offset) {
            val text = builder.tokenText
            if (text != null && text.firstOrNull()?.isLetter() == true) {
                if (current == offset) {
                    result = text.uppercase()
                    break
                }
                current++
            }
            builder.advanceLexer()
        }
        marker.rollbackTo()
        return result
    }

    /** True if [word] appears with [next] as the following letter-word, within the window. Non-consuming. */
    private fun statementContainsWordThen(builder: PsiBuilder, word: String, next: String): Boolean {
        val marker = builder.mark()
        var scanned = 0
        var found = false
        var prevWasWord = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            val text = builder.tokenText
            if (text != null && text.firstOrNull()?.isLetter() == true) {
                if (prevWasWord && text.equals(next, ignoreCase = true)) { found = true; break }
                prevWasWord = text.equals(word, ignoreCase = true)
            } else if (!text.isNullOrBlank()) {
                prevWasWord = false
            }
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
    }

    /** True if [word] appears with the exact token [next] following it. Non-consuming. */
    private fun statementContainsWordThenToken(builder: PsiBuilder, word: String, next: String): Boolean {
        val marker = builder.mark()
        var scanned = 0
        var found = false
        var prevWasWord = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            val text = builder.tokenText
            if (!text.isNullOrBlank()) {
                if (prevWasWord && text == next) { found = true; break }
                prevWasWord = text.equals(word, ignoreCase = true)
            }
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
    }

    /** True if any token before the next ';' matches any of [words] within the window. Non-consuming. */
    private fun statementContainsAny(builder: PsiBuilder, vararg words: String): Boolean {
        val expected = words.toHashSet()
        val marker = builder.mark()
        var scanned = 0
        var found = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            val text = builder.tokenText
            if (text != null && expected.contains(text.uppercase())) {
                found = true
                break
            }
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
    }

    private companion object {
        private const val MAX_LOOKAHEAD = 512
    }
}
