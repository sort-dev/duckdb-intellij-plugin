package dev.sort.duckdb.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import icons.DatabaseIcons

/**
 * DuckDB function completion from the bundled catalog (doris FunctionProvider pattern): every
 * duckdb_functions() name with its kind as the type text, parens inserted with the caret placed
 * between them. Table functions and macros complete in FROM positions too (they ARE relations).
 * Live per-data-source catalogs (extension-aware) replace the bundled list when Stage 4b lands.
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
            for (fn in DuckdbFunctionCatalog.functions) {
                out.addElement(
                    LookupElementBuilder.create(fn.name)
                        .withIcon(kindIcon(fn.kind))
                        .withTypeText(fn.kind.name.lowercase(), true)
                        .withInsertHandler { ctx, _ ->
                            val editor = ctx.editor
                            val at = ctx.tailOffset
                            val already = editor.document.charsSequence.let { at < it.length && it[at] == '(' }
                            if (!already) {
                                editor.document.insertString(at, "()")
                                editor.caretModel.moveToOffset(at + 1)
                            }
                        },
                )
            }
        }
    }
}
