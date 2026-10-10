package dev.sort.duckdb.brikk

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import dev.brikk.house.intellij.ColumnNullability
import dev.brikk.house.intellij.NavigationKind

class BrikkSqlCompletionContributor : CompletionContributor() {
    init { extend(CompletionType.BASIC, PlatformPatterns.psiElement(), Provider) }
    private object Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val file = parameters.originalFile
            if (BrikkDuckdbProfile.forFile(file) == null) return
            val manager = InjectedLanguageManager.getInstance(file.project)
            val sql = manager.getUnescapedText(file)
            val offset = manager.mapInjectedOffsetToUnescaped(file, parameters.offset).coerceIn(0, sql.length)
            val marker = "__brikk_caret"
            val analysis = BrikkSqlScopes.analyze(sql.substring(0, offset) + marker + sql.substring(offset),
                BrikkSqlEditorSupport.contracts(file)) ?: return
            val scoped = analysis.at(offset) ?: return
            if (scoped.kind != BrikkSqlScopes.Kind.COLUMN) return
            val qualifier = scoped.qualifier
            val candidates = if (qualifier.isNotBlank()) listOfNotNull(scoped.scope.relation(qualifier))
                .flatMap { relation -> relation.columns.map { relation to it } } else scoped.scope.columns
            for ((relation, column) in candidates.distinctBy { it.first.alias to it.second.name }) {
                if (!relation.managed) continue
                val type = column.kotlinClassId?.substringAfterLast('.') ?: "unknown type"
                val nullable = when (column.nullability) { ColumnNullability.NON_NULL -> ""; ColumnNullability.NULLABLE -> "?"; else -> " (nullability unknown)" }
                val origin = if (column.navigation?.kind == NavigationKind.RELATION_FALLBACK) "relation contract" else "Kotlin column"
                result.caseInsensitive().addElement(PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(column.name).withTypeText(type + nullable, true)
                        .withTailText("  $origin${relation.alias.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}", true), 120.0))
            }
        }
    }
}
