package dev.sort.duckdb.pipes

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import dev.brikk.house.sql.parser.PipeStageSplitter
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.sort.duckdb.sql.DuckdbSqlDialect

class DuckdbPipesCompletionContributor : CompletionContributor() {
    init { extend(CompletionType.BASIC, PlatformPatterns.psiElement(), Provider) }
    private object Provider : CompletionProvider<CompletionParameters>() {
        val keywords = listOf("WHERE", "SELECT", "EXTEND", "AGGREGATE", "ORDER BY", "LIMIT", "OFFSET",
            "DISTINCT", "JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL OUTER JOIN", "CROSS JOIN", "AS",
            "DROP", "SET", "RENAME", "UNION ALL", "UNION DISTINCT", "INTERSECT", "EXCEPT", "PIVOT", "UNPIVOT")
        private val scopes = object : LinkedHashMap<String, List<String>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > 32
        }
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val file = parameters.originalFile
            if (!file.language.isKindOf(DuckdbSqlDialect.INSTANCE) || !DuckdbPipes.isEnabled(file.project)) return
            val text = file.text
            val chunk = DuckdbPipes.chunkAt(text, parameters.offset.coerceAtMost(text.length)) ?: return
            if (!chunk.hasPipeOperator) return
            val before = chunk.text.take((parameters.offset - chunk.startOffset).coerceIn(0, chunk.text.length))
            val sink = result.caseInsensitive()
            val split = runPipeCatching { PipeStageSplitter.split(before, "duckdb") }.getOrNull() ?: return
            // Resolve the completed input stages, never the partially typed current stage.
            // Unknown base columns stay unknown; aliases introduced by prior SELECT/EXTEND stages
            // are available offline without inventing a schema or offering future aliases.
            var depth = 0
            var boundary = -1
            for (token in split.tokens) when (token.tokenType) {
                TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> depth++
                TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> depth--
                TokenType.PIPE_GT -> if (depth == 0) boundary = token.start
                else -> {}
            }
            if (boundary < 0) return
            val partial = before.substring(boundary + 2).trimStart()
            val completeKeyword = partial.lastOrNull()?.isWhitespace() == true &&
                keywords.any { it.equals(partial.trimEnd(), true) }
            val matchingKeywords = keywords.filter { it.startsWith(partial.trimEnd(), true) }
            if (!completeKeyword && matchingKeywords.isNotEmpty()) {
                val operators = sink.withPrefixMatcher(partial.trimEnd())
                matchingKeywords.forEach { operators.addElement(LookupElementBuilder.create(it).withTypeText("PIPE stage", true)) }
                result.stopHere() // A stage head needs operators, not hundreds of function names.
                return
            }
            val input = before.substring(0, boundary).trim()
            val names = synchronized(scopes) { scopes[input] } ?: runPipeCatching {
                SqlFragment(input, "duckdb").outputShape(ShapeCatalog.EMPTY).names()
            }.getOrNull()?.also { synchronized(scopes) { scopes[input] = it } }.orEmpty()
            names.filter { it != "*" && !it.matches(Regex("_col_\\d+")) }.distinct().forEach {
                sink.addElement(LookupElementBuilder.create(it).withTypeText("PIPE input column", true))
            }
        }
    }
}
