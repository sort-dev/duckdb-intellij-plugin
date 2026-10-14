package dev.sort.duckdb.catalog

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.database.model.DasColumn
import com.intellij.database.symbols.DasSymbol
import com.intellij.database.util.DasUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.sql.psi.SqlResolveResult
import dev.brikk.house.sql.intellij.catalog.DasRelationFacts
import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.intellij.relation.RelationFactsResult
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.PositionKind
import dev.brikk.house.sql.scope.RelationScope
import dev.sort.duckdb.sql.DuckdbSqlDialect

/** Until shared completion also hides native physical columns outside a fully known scope. */
class DuckdbPhysicalColumnScopeContributor : CompletionContributor(), DumbAware {
    private val physical = DasRelationFacts(duckdbDataSourceResolver)

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC || parameters.originalFile.language != DuckdbSqlDialect) return
        val file = parameters.originalFile
        val facts = (physical.facts(file) as? RelationFactsResult.Available)?.facts ?: return
        val source = PipeText.of(file)
        val offset = source.textOffset(parameters.offset).coerceIn(0, source.text.length)
        val statement = SqlScriptSnapshot.parse(source.text, "duckdb", ProgressManager::checkCanceled).at(offset)
            ?.let { it.start until maxOf(it.end, offset) } ?: return
        val completion = RelationScope.complete(source.text, "duckdb", facts, offset, statement, ProgressManager::checkCanceled)
        // The physical-only view cannot prove anything about provider slots, incomplete models,
        // or unsupported SQL. Never use absence from those scopes to hide a platform suggestion.
        if (completion.position != PositionKind.COLUMN || completion.knowledge != Knowledge.FULL) return
        val visible = completion.items.mapNotNull { it.column?.source?.let { source -> source.relation to source.column.name } }.toSet()
        result.runRemainingContributors(parameters) { candidate ->
            val target = candidate.lookupElement.`object`
            val symbol = (target as? SqlResolveResult)?.targetSymbol ?: target
            val column = ((symbol as? DasSymbol)?.dasObject ?: symbol) as? DasColumn
            val table = column?.table
            if (table != null) {
                val path = listOf(DasUtil.getCatalog(table), DasUtil.getSchema(table), table.name).filter { it.isNotBlank() }.joinToString(".")
                if (path to column.name !in visible) return@runRemainingContributors
            }
            result.passResult(candidate)
        }
    }
}
