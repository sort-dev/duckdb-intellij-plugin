package dev.sort.duckdb.sql

import com.intellij.lexer.Lexer
import com.intellij.lexer.LookAheadLexer
import com.intellij.sql.dialects.postgres.PgLexer
import com.intellij.sql.psi.SqlTokens

/**
 * Token-layer bridge from DuckDB syntax to the PG grammar (the DorisLexer technique, DuckDB
 * edition). Each rule either COLLAPSES a DuckDB-only span into one token the grammar can accept
 * as a unit, or MASKS a DuckDB-only modifier as a comment so the surrounding statement keeps its
 * full structured parse. Rules are chosen expression-hole-safe: spans in expression position
 * become a single STRING token (a valid expression), spans in table/type position become a single
 * IDENT, and only droppable modifiers become comments.
 *
 *  - `{'k': v}` struct literals (any nesting)            → one SQL_STRING_TOKEN
 *  - `[x for x in xs]` list comprehensions               → one SQL_STRING_TOKEN
 *  - `INTERVAL 2 days` (unquoted quantity)               → one SQL_STRING_TOKEN
 *  - `STRUCT(...)`/`ROW(...)`/`UNION(...)`/`MAP(...)` as a TYPE (followed-by-`(` inside a
 *    CREATE-columns block or after `::`), incl. `[]` suffix → one SQL_IDENT
 *  - `( FROM ... )` / `( DESCRIBE ... )` etc. (FROM-first subqueries) → one SQL_IDENT
 *  - `FROM 'file.parquet'` (string as table)             → the string re-typed as SQL_IDENT
 *  - `QUALIFY`                                           → HAVING token (StarRocks trick)
 *  - `GROUP/ORDER BY ALL`                                → ALL re-typed as SQL_IDENT
 *  - `OR REPLACE` after CREATE when followed by TABLE    → comment (PG lacks OR REPLACE TABLE)
 *  - `BY NAME` after INSERT-target / set operators       → comment
 *  - `* EXCLUDE(...)` / `* REPLACE(...)`                 → comment (doris EXCEPT-mask port)
 *  - `USING SAMPLE ...` suffix / `TABLESAMPLE ...`       → comment
 *  - `TRY_CAST(x AS t)`'s ` AS t` tail                   → comment (call parses as 1-arg fn)
 *
 * This lexer also feeds the editor highlighter (the platform builds the highlighting lexer from
 * the ParserDefinition), so comment-masked spans render comment-colored until
 * [DuckdbMaskedSpanRecolorAnnotator] repaints them; string-collapsed spans render string-colored
 * (acceptable v1 — struct literals ARE mostly literal content).
 */
class DuckdbLexer : LookAheadLexer(PgLexer()) {

    private var parenDepth = 0
    private var inCreateColumns = false
    private var createColumnsDepth = 0
    private var lastMeaningful: String? = null
    private var statementHead: String? = null
    private val tryCastAsDepths = ArrayDeque<Int>()

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        parenDepth = 0
        inCreateColumns = false
        createColumnsDepth = 0
        lastMeaningful = null
        statementHead = null
        tryCastAsDepths.clear()
        super.start(buffer, startOffset, endOffset, initialState)
    }

    override fun lookAhead(baseLexer: Lexer) {
        val text = baseLexer.tokenText
        val upper = text.uppercase()
        when {
            text == "{" -> { collapseBraceSpan(baseLexer); return }
            text == "[" && (bracketIsComprehension(baseLexer) || lastMeaningful in setOf("(", ",", "=")) -> {
                collapseBracketSpan(baseLexer); return
            }
            upper == "INTERVAL" && (nextIsNumber(baseLexer) || nextIs(baseLexer, "(")) -> {
                collapseInterval(baseLexer); return
            }
            upper == "QUALIFY" -> { advanceAs(baseLexer, SqlTokens.SQL_HAVING); noteToken(text); return }
            upper == "ALL" && (lastMeaningful == "BY") -> { advanceAs(baseLexer, SqlTokens.SQL_IDENT); noteToken(text); return }
            upper == "OR" && lastMeaningful == "CREATE" && wordsFollow(baseLexer, "REPLACE", "TABLE") -> {
                maskWords(baseLexer, 2); return // OR REPLACE -> comment; CREATE TABLE survives
            }
            upper == "BY" && wordsFollow(baseLexer, "NAME") &&
                lastMeaningful in setOf("UNION", "EXCEPT", "INTERSECT") -> { maskWords(baseLexer, 2); return }
            upper == "BY" && wordsFollow(baseLexer, "NAME", "SELECT") -> { maskWords(baseLexer, 2); return }
            upper == "BY" && wordsFollow(baseLexer, "NAME", "VALUES") -> { maskWords(baseLexer, 2); return }
            (upper == "EXCLUDE" || upper == "REPLACE") && lastMeaningful == "*" && nextIs(baseLexer, "(") -> {
                maskThroughMatchingParen(baseLexer); return
            }
            upper == "FILTER" && nextIs(baseLexer, "(") && !parenStartsWithWhere(baseLexer) -> {
                maskThroughMatchingParen(baseLexer); return
            }
            upper == "VALUES" && lastMeaningful == "FROM" -> { collapseValuesTableFactor(baseLexer); return }
            upper == "LAMBDA" -> { maskLambdaHead(baseLexer); return }
            (upper == "ASOF" || upper == "ANTI" || upper == "SEMI") && nextWordIsJoinish(baseLexer) -> {
                maskWords(baseLexer, 1); return
            }
            upper == "USING" && wordsFollow(baseLexer, "SAMPLE") -> { maskSampleSuffix(baseLexer); return }
            upper == "TABLESAMPLE" -> { maskSampleSuffix(baseLexer); return }
            upper in TYPE_COLLAPSE_HEADS && nextIs(baseLexer, "(") &&
                (inCreateColumns || lastMeaningful == "::" || lastMeaningful == "TYPE") -> {
                collapseTypeSpan(baseLexer); return
            }
            text == "(" && lastMeaningful in setOf("FROM", "JOIN", ",", "(") &&
                parenLeadsFromFirst(baseLexer) -> { collapseParenAsIdent(baseLexer); return }
            upper == "TRY_CAST" && nextIs(baseLexer, "(") -> {
                tryCastAsDepths.addLast(parenDepth + 1)
                trackStructure(text); noteToken(upper)
                super.lookAhead(baseLexer)
                return
            }
            upper == "AS" && tryCastAsDepths.isNotEmpty() && parenDepth == tryCastAsDepths.last() -> {
                maskUntilCloseParenAtCurrentDepth(baseLexer)
                return
            }
            upper == "AS" && inCreateColumns && createColumnsDepth > 0 &&
                parenDepth == createColumnsDepth && nextIs(baseLexer, "(") -> {
                collapseTypeSpan(baseLexer) // AS (expr) -> one ident (generated-column expr)
                return
            }
            upper == "GENERATED" && inCreateColumns && wordsFollow(baseLexer, "ALWAYS", "AS") -> {
                maskGeneratedClause(baseLexer); return // PG demands STORED; DuckDB omits it
            }
            upper == "BLOB" && (inCreateColumns || lastMeaningful == "::" || lastMeaningful == "AS") -> {
                advanceAs(baseLexer, SqlTokens.SQL_IDENT) // BLOB is a type name in DuckDB
                noteToken(upper)
                return
            }
            upper == "DOUBLE" && !wordsFollow(baseLexer, "PRECISION") &&
                (inCreateColumns || lastMeaningful == "::") -> {
                advanceAs(baseLexer, SqlTokens.SQL_IDENT) // bare DOUBLE is a type in DuckDB
                noteToken(upper)
                return
            }
            upper == "COLUMN" && statementHead == "ALTER" && dottedIdentFollows(baseLexer) -> {
                noteToken(upper); trackStructure(text)
                super.lookAhead(baseLexer) // emit COLUMN itself
                collapseDottedPath(baseLexer)
                return
            }
            upper == "TABLE" && statementHead == "CREATE" -> {
                noteToken(upper); trackStructure(text)
                super.lookAhead(baseLexer)
                var guard = 4
                while (baseLexer.tokenType != null && baseLexer.tokenText.isNullOrBlank() && guard-- > 0) {
                    super.lookAhead(baseLexer)
                }
                if (baseLexer.tokenType == SqlTokens.SQL_STRING_TOKEN) {
                    advanceAs(baseLexer, SqlTokens.SQL_IDENT) // CREATE TABLE 'file-ish name'
                }
                return
            }
            (upper == "FROM" || upper == "JOIN") && statementHead != "COPY" -> {
                noteToken(upper); trackStructure(text)
                super.lookAhead(baseLexer)
                var guard = 4
                while (baseLexer.tokenType != null && baseLexer.tokenText.isNullOrBlank() && guard-- > 0) {
                    super.lookAhead(baseLexer)
                }
                if (baseLexer.tokenType == SqlTokens.SQL_STRING_TOKEN) {
                    advanceAs(baseLexer, SqlTokens.SQL_IDENT) // FROM 'file.parquet'
                }
                return
            }
        }
        trackStructure(text)
        noteToken(if (text == "*") "*" else upper)
        super.lookAhead(baseLexer)
    }

    // --- span collapsers -------------------------------------------------------------------

    /** `{ ... }` (nested) as ONE string token — a struct/map literal is a valid expression unit. */
    private fun collapseBraceSpan(base: Lexer) {
        var depth = 0
        do {
            when (base.tokenText) {
                "{" -> depth++
                "}" -> depth--
            }
            base.advance()
        } while (base.tokenType != null && depth > 0)
        addToken(base.tokenStart, SqlTokens.SQL_STRING_TOKEN)
        noteToken("}")
    }

    /** `[ x for x in xs ]` as ONE string token (list comprehension). */
    private fun collapseBracketSpan(base: Lexer) {
        var depth = 0
        do {
            when (base.tokenText) {
                "[" -> depth++
                "]" -> depth--
            }
            base.advance()
        } while (base.tokenType != null && depth > 0)
        addToken(base.tokenStart, SqlTokens.SQL_STRING_TOKEN)
        noteToken("]")
    }

    /** `INTERVAL 2 days` → one string token (PG only accepts the quoted form). */
    private fun collapseInterval(base: Lexer) {
        base.advance() // INTERVAL
        skipWs(base)
        if (base.tokenText == "(") { // INTERVAL (expr) unit
            var depth = 0
            do {
                when (base.tokenText) { "(" -> depth++; ")" -> depth-- }
                base.advance()
            } while (base.tokenType != null && depth > 0)
        } else {
            base.advance() // the number
        }
        skipWs(base)
        if (base.tokenType == SqlTokens.SQL_IDENT || base.tokenText?.firstOrNull()?.isLetter() == true) {
            base.advance() // the unit word
        }
        addToken(base.tokenStart, SqlTokens.SQL_STRING_TOKEN)
        noteToken("INTERVAL_LIT")
    }

    /** `STRUCT(...)`/`ROW(...)`/`UNION(...)`/`MAP(...)` (+ optional `[]`) as ONE ident (a type). */
    private fun collapseTypeSpan(base: Lexer) {
        base.advance() // head word
        skipWs(base)
        var depth = 0
        do {
            when (base.tokenText) {
                "(" -> depth++
                ")" -> depth--
            }
            base.advance()
        } while (base.tokenType != null && depth > 0)
        // optional array suffixes: [] (possibly repeated; whitespace may precede)
        while (true) {
            skipWs(base)
            if (base.tokenText != "[") break
            base.advance()
            if (base.tokenText == "]") base.advance() else break
        }
        addToken(base.tokenStart, SqlTokens.SQL_IDENT)
        noteToken("TYPE_COLLAPSED")
    }

    /** `( FROM ... )` / `( DESCRIBE ... )` — collapse the paren group to ONE ident (a table ref). */
    private fun collapseParenAsIdent(base: Lexer) {
        var depth = 0
        do {
            when (base.tokenText) {
                "(" -> depth++
                ")" -> depth--
            }
            base.advance()
        } while (base.tokenType != null && depth > 0)
        addToken(base.tokenStart, SqlTokens.SQL_IDENT)
        noteToken("SUBQUERY_COLLAPSED")
    }

    // --- comment masks ---------------------------------------------------------------------

    /** Mask the next [words] letter-words (plus interleaved whitespace) as one block comment. */
    private fun maskWords(base: Lexer, words: Int) {
        var remaining = words
        while (base.tokenType != null && remaining > 0) {
            if (base.tokenText?.firstOrNull()?.isLetter() == true) remaining--
            base.advance()
        }
        addToken(base.tokenStart, SqlTokens.SQL_BLOCK_COMMENT)
        // deliberately NOT noting: `CREATE [masked OR REPLACE] TABLE` must still read as
        // CREATE→TABLE for the create-columns tracker.
    }

    /** Mask `GENERATED ALWAYS AS ( expr ) [VIRTUAL|STORED]` as one comment (column def survives). */
    private fun maskGeneratedClause(base: Lexer) {
        base.advance() // GENERATED
        var guard = 6
        while (base.tokenType != null && guard-- > 0 && base.tokenText != "(") base.advance()
        var depth = 0
        do {
            when (base.tokenText) { "(" -> depth++; ")" -> depth-- }
            base.advance()
        } while (base.tokenType != null && depth > 0)
        skipWs(base)
        val u = base.tokenText?.uppercase()
        if (u == "VIRTUAL" || u == "STORED") base.advance()
        addToken(base.tokenStart, SqlTokens.SQL_BLOCK_COMMENT)
        noteToken("MASKED")
    }

    /** Inside TRY_CAST(x AS t): mask from AS up to (not including) the call's close paren. */
    private fun maskUntilCloseParenAtCurrentDepth(base: Lexer) {
        var depth = 0
        base.advance() // AS
        while (base.tokenType != null) {
            val t = base.tokenText
            if (t == "(") depth++
            if (t == ")") {
                if (depth == 0) break
                depth--
            }
            if (t == ";") break
            base.advance()
        }
        addToken(base.tokenStart, SqlTokens.SQL_BLOCK_COMMENT)
        noteToken("MASKED")
    }

    /** Mask `EXCLUDE (...)` / `REPLACE (...)` through the matching close paren. */
    private fun maskThroughMatchingParen(base: Lexer) {
        base.advance() // head word
        skipWs(base)
        var depth = 0
        do {
            when (base.tokenText) {
                "(" -> depth++
                ")" -> depth--
            }
            base.advance()
        } while (base.tokenType != null && depth > 0)
        addToken(base.tokenStart, SqlTokens.SQL_BLOCK_COMMENT)
        noteToken("MASKED")
    }

    /**
     * Mask a sample suffix: `USING SAMPLE <spec>` / `TABLESAMPLE <spec>` where spec runs through
     * numbers, %/(...) groups and sampling words, stopping at anything structural.
     */
    private fun maskSampleSuffix(base: Lexer) {
        base.advance() // USING or TABLESAMPLE
        var guards = 24
        while (base.tokenType != null && guards-- > 0) {
            val t = base.tokenText ?: break
            val u = t.uppercase()
            val isSpec = u == "SAMPLE" || u == "%" || u == "PERCENT" || u == "ROWS" ||
                u == "BERNOULLI" || u == "SYSTEM" || u == "RESERVOIR" || u == "REPEATABLE" ||
                t == "(" ||
                t.firstOrNull()?.isDigit() == true || t.isBlank()
            if (!isSpec) break
            if (t == "(") { // consume the whole group
                var depth = 0
                do {
                    when (base.tokenText) {
                        "(" -> depth++
                        ")" -> depth--
                    }
                    base.advance()
                } while (base.tokenType != null && depth > 0)
                continue
            }
            base.advance()
        }
        addToken(base.tokenStart, SqlTokens.SQL_BLOCK_COMMENT)
        noteToken("MASKED")
    }

    // --- probes (non-consuming; operate on raw buffer, cheap) ------------------------------

    private fun nextIs(base: Lexer, expected: String): Boolean {
        val seq = base.bufferSequence
        var i = base.tokenEnd
        while (i < seq.length && seq[i].isWhitespace()) i++
        return i < seq.length && seq.startsWith(expected, i)
    }

    private fun nextIsNumber(base: Lexer): Boolean {
        val seq = base.bufferSequence
        var i = base.tokenEnd
        while (i < seq.length && seq[i].isWhitespace()) i++
        return i < seq.length && seq[i].isDigit()
    }

    /** The following letter-words (case-insensitive), skipping whitespace, match [words] in order. */
    private fun wordsFollow(base: Lexer, vararg words: String): Boolean {
        val seq = base.bufferSequence
        var i = base.tokenEnd
        for (w in words) {
            while (i < seq.length && seq[i].isWhitespace()) i++
            val end = i + w.length
            if (end > seq.length || !seq.subSequence(i, end).toString().equals(w, ignoreCase = true)) return false
            if (end < seq.length && (seq[end].isLetterOrDigit() || seq[end] == '_')) return false
            i = end
        }
        return true
    }

    /** `[` opens a comprehension iff a bare ` for ` appears before the matching `]`. */
    private fun bracketIsComprehension(base: Lexer): Boolean {
        val seq = base.bufferSequence
        var i = base.tokenEnd
        var depth = 1
        var quote: Char? = null
        while (i < seq.length && depth > 0) {
            val c = seq[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '[' -> depth++
                c == ']' -> depth--
                (c == 'f' || c == 'F') && depth == 1 &&
                    i + 3 < seq.length && seq.subSequence(i, i + 3).toString().equals("for", true) &&
                    (i == 0 || !seq[i - 1].isLetterOrDigit()) && !seq[i + 3].isLetterOrDigit() -> return true
            }
            i++
        }
        return false
    }

    /** `FROM VALUES (…),(…)` — collapse the bare VALUES table factor to ONE ident; a trailing
     *  `t(a)` alias then reads as a normal aliased table ref. (PG only allows parenthesized
     *  VALUES in FROM; DuckDB allows it bare.) */
    private fun collapseValuesTableFactor(base: Lexer) {
        base.advance() // VALUES
        var guard = 64
        while (base.tokenType != null && guard-- > 0) {
            skipWs(base)
            when (base.tokenText) {
                "(" -> {
                    var depth = 0
                    do {
                        when (base.tokenText) { "(" -> depth++; ")" -> depth-- }
                        base.advance()
                    } while (base.tokenType != null && depth > 0)
                }
                "," -> base.advance()
                else -> break
            }
        }
        addToken(base.tokenStart, SqlTokens.SQL_IDENT)
        noteToken("VALUES_TABLE")
    }

    /** Mask `lambda x[, y]*:` (python-style lambda head) as one comment; the body survives. */
    private fun maskLambdaHead(base: Lexer) {
        base.advance() // LAMBDA
        var guard = 12
        while (base.tokenType != null && guard-- > 0 && base.tokenText != ":") {
            val t = base.tokenText ?: break
            if (t == "(" || t == ")" || t == ";") break
            base.advance()
        }
        if (base.tokenText == ":") base.advance()
        addToken(base.tokenStart, SqlTokens.SQL_BLOCK_COMMENT)
        noteToken("MASKED_LAMBDA")
    }

    /** Next word is JOIN or a join qualifier (LEFT/RIGHT/FULL/INNER/OUTER). */
    private fun nextWordIsJoinish(base: Lexer): Boolean =
        listOf("JOIN", "LEFT", "RIGHT", "FULL", "INNER", "OUTER").any { wordsFollow(base, it) }

    /** ident(.ident)+ follows on the raw buffer (a dotted column path). */
    private fun dottedIdentFollows(base: Lexer): Boolean {
        val seq = base.bufferSequence
        var i = base.tokenEnd
        while (i < seq.length && seq[i].isWhitespace()) i++
        val start = i
        while (i < seq.length && (seq[i].isLetterOrDigit() || seq[i] == '_')) i++
        return i > start && i < seq.length && seq[i] == '.'
    }

    /** Collapse `a.b.c` into ONE ident token. */
    private fun collapseDottedPath(base: Lexer) {
        skipWs(base)
        var guard = 16
        while (base.tokenType != null && guard-- > 0) {
            base.advance() // ident
            if (base.tokenText != ".") break
            base.advance() // dot
        }
        addToken(base.tokenStart, SqlTokens.SQL_IDENT)
        noteToken("DOTTED_COLUMN")
    }

    /** After FILTER: does the upcoming paren group start with WHERE (the PG-legal form)? */
    private fun parenStartsWithWhere(base: Lexer): Boolean {
        val seq = base.bufferSequence
        var i = base.tokenEnd
        while (i < seq.length && seq[i].isWhitespace()) i++
        if (i >= seq.length || seq[i] != '(') return false
        i++
        while (i < seq.length && seq[i].isWhitespace()) i++
        return i + 5 <= seq.length && seq.subSequence(i, i + 5).toString().equals("WHERE", true)
    }

    /** `(` immediately leading a FROM-first/DESCRIBE/SUMMARIZE/PIVOT subquery. */
    private fun parenLeadsFromFirst(base: Lexer): Boolean {
        val seq = base.bufferSequence
        var i = base.tokenEnd
        while (i < seq.length && seq[i].isWhitespace()) i++
        for (head in PAREN_FROM_FIRST_HEADS) {
            val end = i + head.length
            if (end <= seq.length && seq.subSequence(i, end).toString().equals(head, true) &&
                (end == seq.length || !seq[end].isLetterOrDigit())
            ) return true
        }
        return false
    }

    private fun skipWs(base: Lexer) {
        while (base.tokenType != null && base.tokenText.isNullOrBlank()) base.advance()
    }

    private fun trackStructure(text: String?) {
        when (text) {
            "(" -> {
                parenDepth++
                if (inCreateColumns && createColumnsDepth == 0) createColumnsDepth = parenDepth
            }
            ")" -> {
                if (inCreateColumns && parenDepth == createColumnsDepth) {
                    inCreateColumns = false
                    createColumnsDepth = 0
                }
                parenDepth--
                // pop TRY_CAST frames whose parens just closed (nested try_cast support)
                while (tryCastAsDepths.isNotEmpty() && tryCastAsDepths.last() > parenDepth) {
                    tryCastAsDepths.removeLast()
                }
            }
            ";" -> {
                inCreateColumns = false
                createColumnsDepth = 0
                parenDepth = 0
            }
        }
    }

    private fun noteToken(upperOrSymbol: String?) {
        val t = upperOrSymbol ?: return
        if (t.isBlank()) return
        if (statementHead == null && t.firstOrNull()?.isLetter() == true) statementHead = t
        if (t == ";") statementHead = null
        if (t == "TABLE" && lastMeaningful == "CREATE") inCreateColumns = true
        if (t == "TABLE" && lastMeaningful == "REPLACE") inCreateColumns = true
        lastMeaningful = t
    }

    private companion object {
        private val TYPE_COLLAPSE_HEADS = setOf("STRUCT", "ROW", "UNION", "MAP")
        private val PAREN_FROM_FIRST_HEADS = listOf("FROM", "DESCRIBE", "SUMMARIZE", "PIVOT", "UNPIVOT")
    }
}
