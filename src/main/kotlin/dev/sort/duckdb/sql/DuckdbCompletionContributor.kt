package dev.sort.duckdb.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import dev.sort.duckdb.catalog.DuckdbCatalogResolver
import dev.sort.duckdb.catalog.DuckdbExtensionOffers
import icons.DatabaseIcons

/**
 * DuckDB function completion (doris FunctionProvider pattern): every function of the ACTIVE
 * catalog with its kind as the type text, parens inserted with the caret placed between them.
 * Table functions and macros complete in FROM positions too (they ARE relations).
 *
 * The active catalog comes from [DuckdbCatalogResolver]: the editor's data source's live
 * duckdb_functions() harvest when one exists (REPLACING the bundled list — version-exact both
 * ways), the bundled snapshot otherwise. On top, [DuckdbExtensionOffers] adds functions specific
 * extensions would provide (`requires <extension>` type text) — only names the active catalog
 * does not already have, matched case-folded (DuckDB resolves function names case-insensitively).
 */
class DuckdbCompletionContributor : CompletionContributor() {

    init {
        // scope comes from the plugin.xml registration (language="DuckDBBrikk") — a
        // withLanguage(...) pattern here does NOT match completion-position leaves and would
        // silently disable the provider (empirically bisected).
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), FunctionProvider)
    }

    private object FunctionProvider : CompletionProvider<CompletionParameters>() {
        // doris parity (0.6.0 function-kind icons): distinct icons per kind, same icon set.
        private fun kindIcon(kind: DuckdbFunctionCatalog.Kind): javax.swing.Icon = when (kind) {
            DuckdbFunctionCatalog.Kind.AGGREGATE -> DatabaseIcons.Aggregate
            DuckdbFunctionCatalog.Kind.TABLE, DuckdbFunctionCatalog.Kind.TABLE_MACRO -> DatabaseIcons.Table
            DuckdbFunctionCatalog.Kind.MACRO -> DatabaseIcons.Routine
            else -> DatabaseIcons.Function
        }

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val out = result.caseInsensitive()
            val active = DuckdbCatalogResolver.activeCatalogFor(parameters.originalFile)
            for (fn in active.functions) {
                out.addElement(element(fn.name, fn.kind, fn.kind.name.lowercase()))
            }
            // Extension-aware offers: only names the active catalog (live or bundled) lacks —
            // case-folded match, original case inserted (`ST_Area` stays `ST_Area`).
            val have = active.functions.mapTo(HashSet(active.functions.size * 2)) { it.name.lowercase() }
            for (offer in DuckdbExtensionOffers.offers) {
                if (offer.name.lowercase() in have) continue
                out.addElement(element(offer.name, offer.kind, "requires ${offer.extension}"))
            }
        }

        private fun element(name: String, kind: DuckdbFunctionCatalog.Kind, typeText: String) =
            LookupElementBuilder.create(name)
                .withIcon(kindIcon(kind))
                .withTypeText(typeText, true)
                .withInsertHandler { ctx, _ ->
                    val editor = ctx.editor
                    val at = ctx.tailOffset
                    val already = editor.document.charsSequence.let { at < it.length && it[at] == '(' }
                    if (!already) {
                        editor.document.insertString(at, "()")
                        editor.caretModel.moveToOffset(at + 1)
                    }
                }
    }
}
