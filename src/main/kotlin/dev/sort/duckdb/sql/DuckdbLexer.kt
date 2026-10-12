package dev.sort.duckdb.sql

import com.intellij.lexer.Lexer
import com.intellij.sql.dialects.postgres.PgLexer
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.lexer.CastTargetMask
import dev.brikk.house.sql.intellij.lexer.MaskingSqlLexer

class DuckdbLexer : MaskingSqlLexer(PgLexer(), CastTargetMask()) {
    private var inCreateColumns = false
    private var createColumnsDepth = 0

    override fun resetStatement() {
        inCreateColumns = false
        createColumnsDepth = 0
    }

    override fun onNote(token: String) {
        if (token == "TABLE" && lastMeaningful in setOf("CREATE", "REPLACE")) inCreateColumns = true
    }

    override fun onOpenParen(depth: Int) {
        if (inCreateColumns && createColumnsDepth == 0) createColumnsDepth = depth
    }

    override fun onCloseParen(depth: Int) {
        if (inCreateColumns && depth == createColumnsDepth) {
            inCreateColumns = false
            createColumnsDepth = 0
        }
    }

    override fun rule(base: Lexer, upper: String): Boolean {
        val text = base.tokenText
        when {
            text == "{" -> {
                consumeBalanced(base, "{", "}")
                span(base, SqlTokens.SQL_STRING_TOKEN, "}")
            }
            text == "[" && (bracketIsComprehension(base) || lastMeaningful in setOf("(", ",", "=")) -> {
                consumeBalanced(base, "[", "]")
                span(base, SqlTokens.SQL_STRING_TOKEN, "]")
            }
            upper == "INTERVAL" && (nextIsDigit(base) || nextIs(base, "(")) -> collapseInterval(base)
            upper == "QUALIFY" -> emitAs(base, SqlTokens.SQL_HAVING)
            upper == "ALL" && lastMeaningful == "BY" -> emitAs(base, SqlTokens.SQL_IDENT)
            upper == "OR" && lastMeaningful == "CREATE" && wordsFollow(base, "REPLACE", "TABLE") -> maskWords(base, 2)
            upper == "BY" && wordsFollow(base, "NAME") &&
                lastMeaningful in setOf("UNION", "EXCEPT", "INTERSECT") -> maskWords(base, 2)
            upper == "BY" && (wordsFollow(base, "NAME", "SELECT") || wordsFollow(base, "NAME", "VALUES")) -> maskWords(base, 2)
            upper in setOf("EXCLUDE", "REPLACE") && lastMeaningful == "*" && nextIs(base, "(") -> maskWithGroup(base)
            upper == "FILTER" && nextIs(base, "(") && !parenStartsWithWhere(base) -> maskWithGroup(base)
            upper == "VALUES" && lastMeaningful == "FROM" -> collapseValuesTableFactor(base)
            upper == "LAMBDA" -> maskLambdaHead(base)
            upper in setOf("ASOF", "ANTI", "SEMI") && nextWordIsJoinish(base) -> maskWords(base, 1)
            upper == "USING" && wordsFollow(base, "SAMPLE") || upper == "TABLESAMPLE" -> maskSampleSuffix(base)
            upper in TYPE_COLLAPSE_HEADS && nextIs(base, "(") &&
                (inCreateColumns || lastMeaningful == "::" || lastMeaningful == "TYPE") -> collapseTypeSpan(base)
            text == "(" && lastMeaningful in setOf("FROM", "JOIN", ",", "(") && parenLeadsFromFirst(base) -> {
                consumeBalanced(base)
                span(base, SqlTokens.SQL_IDENT, "SUBQUERY_COLLAPSED")
            }
            upper == "AS" && inCreateColumns && createColumnsDepth > 0 &&
                parenDepth == createColumnsDepth && nextIs(base, "(") -> collapseTypeSpan(base)
            upper == "GENERATED" && inCreateColumns && wordsFollow(base, "ALWAYS", "AS") -> maskGeneratedClause(base)
            upper == "BLOB" && (inCreateColumns || lastMeaningful == "::" || lastMeaningful == "AS") -> emitAs(base, SqlTokens.SQL_IDENT)
            upper == "DOUBLE" && !wordsFollow(base, "PRECISION") &&
                (inCreateColumns || lastMeaningful == "::") -> emitAs(base, SqlTokens.SQL_IDENT)
            upper == "COLUMN" && statementHead == "ALTER" && dottedIdentFollows(base) -> {
                emit(base)
                collapseDottedPath(base)
            }
            upper == "TABLE" && statementHead == "CREATE" ||
                upper in setOf("FROM", "JOIN") && statementHead != "COPY" -> emitTableHead(base)
            else -> return false
        }
        return true
    }

    private fun emitTableHead(base: Lexer) {
        emit(base)
        var guard = 4
        while (base.tokenType != null && base.tokenText.isBlank() && guard-- > 0) advanceLexer(base)
        if (base.tokenType == SqlTokens.SQL_STRING_TOKEN) advanceAs(base, SqlTokens.SQL_IDENT)
    }

    private fun collapseInterval(base: Lexer) {
        base.advance()
        skipWhitespaceTokens(base)
        if (base.tokenText == "(") consumeBalanced(base) else base.advance()
        skipWhitespaceTokens(base)
        if (base.tokenType == SqlTokens.SQL_IDENT || base.tokenText?.firstOrNull()?.isLetter() == true) base.advance()
        span(base, SqlTokens.SQL_STRING_TOKEN, "INTERVAL_LIT")
    }

    private fun collapseTypeSpan(base: Lexer) {
        base.advance()
        skipWhitespaceTokens(base)
        consumeBalanced(base)
        while (true) {
            skipWhitespaceTokens(base)
            if (base.tokenText != "[") break
            base.advance()
            if (base.tokenText == "]") base.advance() else break
        }
        span(base, SqlTokens.SQL_IDENT, "TYPE_COLLAPSED")
    }

    private fun maskGeneratedClause(base: Lexer) {
        base.advance()
        var guard = 6
        while (base.tokenType != null && guard-- > 0 && base.tokenText != "(") base.advance()
        consumeBalanced(base)
        skipWhitespaceTokens(base)
        if (base.tokenText?.uppercase() in setOf("VIRTUAL", "STORED")) base.advance()
        span(base, SqlTokens.SQL_BLOCK_COMMENT, MASKED)
    }

    private fun maskSampleSuffix(base: Lexer) {
        base.advance()
        var guard = 24
        while (base.tokenType != null && guard-- > 0) {
            val text = base.tokenText
            val upper = text.uppercase()
            if (upper !in SAMPLE_WORDS && text != "(" && text.firstOrNull()?.isDigit() != true && !text.isBlank()) break
            if (text == "(") consumeBalanced(base) else base.advance()
        }
        span(base, SqlTokens.SQL_BLOCK_COMMENT, MASKED)
    }

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

    private fun collapseValuesTableFactor(base: Lexer) {
        base.advance()
        var guard = 64
        while (base.tokenType != null && guard-- > 0) {
            skipWhitespaceTokens(base)
            when (base.tokenText) {
                "(" -> consumeBalanced(base)
                "," -> base.advance()
                else -> break
            }
        }
        span(base, SqlTokens.SQL_IDENT, "VALUES_TABLE")
    }

    private fun maskLambdaHead(base: Lexer) {
        base.advance()
        var guard = 12
        while (base.tokenType != null && guard-- > 0 && base.tokenText != ":") {
            if (base.tokenText in setOf("(", ")", ";")) break
            base.advance()
        }
        if (base.tokenText == ":") base.advance()
        span(base, SqlTokens.SQL_BLOCK_COMMENT, "MASKED_LAMBDA")
    }

    private fun nextWordIsJoinish(base: Lexer): Boolean =
        listOf("JOIN", "LEFT", "RIGHT", "FULL", "INNER", "OUTER").any { wordsFollow(base, it) }

    private fun dottedIdentFollows(base: Lexer): Boolean {
        val seq = base.bufferSequence
        val start = offsetAfterWhitespace(base, base.tokenEnd)
        val end = start + bufferWord(base, start).length
        return end > start && seq.getOrNull(end) == '.'
    }

    private fun collapseDottedPath(base: Lexer) {
        skipWhitespaceTokens(base)
        var guard = 16
        while (base.tokenType != null && guard-- > 0) {
            base.advance()
            if (base.tokenText != ".") break
            base.advance()
        }
        span(base, SqlTokens.SQL_IDENT, "DOTTED_COLUMN")
    }

    private fun parenStartsWithWhere(base: Lexer): Boolean {
        val seq = base.bufferSequence
        var i = offsetAfterWhitespace(base, base.tokenEnd)
        if (seq.getOrNull(i) != '(') return false
        i = offsetAfterWhitespace(base, i + 1)
        return i + 5 <= seq.length && seq.subSequence(i, i + 5).toString().equals("WHERE", true)
    }

    private fun parenLeadsFromFirst(base: Lexer): Boolean {
        val seq = base.bufferSequence
        val i = offsetAfterWhitespace(base, base.tokenEnd)
        return PAREN_FROM_FIRST_HEADS.any { head ->
            val end = i + head.length
            end <= seq.length && seq.subSequence(i, end).toString().equals(head, true) &&
                (end == seq.length || !seq[end].isLetterOrDigit())
        }
    }

    private companion object {
        val TYPE_COLLAPSE_HEADS = setOf("STRUCT", "ROW", "UNION", "MAP")
        val PAREN_FROM_FIRST_HEADS = listOf("FROM", "DESCRIBE", "SUMMARIZE", "PIVOT", "UNPIVOT")
        val SAMPLE_WORDS = setOf("SAMPLE", "%", "PERCENT", "ROWS", "BERNOULLI", "SYSTEM", "RESERVOIR", "REPEATABLE")
    }
}
