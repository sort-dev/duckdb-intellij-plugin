package dev.sort.duckdb.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import dev.sort.duckdb.catalog.DuckdbAutoIntrospect
import dev.sort.duckdb.catalog.DuckdbCatalogResolver
import dev.sort.duckdb.catalog.DuckdbExtensionOffers
import dev.sort.duckdb.catalog.DuckdbIntrospectNotifier
import dev.sort.duckdb.catalog.DuckdbNamespacePath
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
        // scope comes from the plugin.xml registration (language="DuckDBSQL") — a
        // withLanguage(...) pattern here does NOT match completion-position leaves and would
        // silently disable the provider (empirically bisected).
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), FunctionProvider)
        // Lazy/targeted schema-tree deepening: path-typing into an enumerated-but-childless
        // namespace kicks a one-level introspection of exactly that node (offers nothing itself).
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), QualifiedPathIntrospectProvider)
    }

    /**
     * Auto-introspection trigger: completion invoked right after a qualified db-object path
     * (`side_db.<caret>`, `side_db.main.<caret>`, or a bare `main.<caret>` when exactly one catalog
     * offers that schema) that resolves to an enumerated-but-childless namespace kicks a TARGETED
     * one-level refresh of exactly that node, plus an editor banner. Offers nothing itself — the
     * platform's own SQL completion supplies the freshly-loaded names once the async introspection
     * lands (invoke completion again). See [dev.sort.duckdb.catalog.DuckdbAutoIntrospect].
     */
    private object QualifiedPathIntrospectProvider : CompletionProvider<CompletionParameters>() {
        // The dotted chain right before the caret: (optionally quoted) identifier segments, then a
        // dot, optional whitespace, and the partial word being typed. Group 1 = the chain WITHOUT
        // the trailing dot. DuckDB delimits identifiers with double quotes and also accepts
        // backticks, so both are stripped; single quotes are strings and never appear here.
        private val PATH_BEFORE_CARET =
            Regex("""([A-Za-z_"`][\w"`]*(?:\.[A-Za-z_"`][\w"`]*)*)\.\s*\w*$""")

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val file = parameters.originalFile
            if (!file.language.isKindOf(DuckdbSqlDialect.INSTANCE)) return
            runCatching {
                val text = file.text
                val offset = parameters.offset.coerceAtMost(text.length)
                val tail = text.substring((offset - 200).coerceAtLeast(0), offset)
                val parts = PATH_BEFORE_CARET.find(tail)?.groupValues?.get(1)
                    ?.split('.')?.map { it.trim('"', '`') }?.filter { it.isNotEmpty() }
                    ?: return
                if (parts.isEmpty() || parts.size > 2) return  // only catalog / catalog.schema deepen

                val local = DuckdbCatalogResolver.dataSourceFor(file) ?: return
                val facade = DbPsiFacade.getInstance(file.project)
                val dataSource = facade.findDataSource(local.uniqueId)
                    ?: facade.dataSources.firstOrNull { it.uniqueId == local.uniqueId }
                    ?: return
                // The model must come from the DbDataSource wrapper, NOT LocalDataSource.model
                // (doris round 6: the LocalDataSource's own object list is silently empty).
                val roots = dataSource.model.modelRoots.map(::DasNode).toList()
                val deepen = DuckdbNamespacePath.decideDeepen(roots, parts) ?: return

                val realNode = (deepen.node as? DasNode)?.das
                DuckdbAutoIntrospect.request(
                    file.project, local, deepen.level, deepen.catalog, deepen.schema, realNode,
                )
                // Banner whether THIS pass kicked the refresh or an earlier one already did (the
                // request de-dupes internally; one STABLE message so it never flickers on repeated
                // keystrokes into the same namespace).
                DuckdbIntrospectNotifier.reportPending(
                    file.project, file.viewProvider.virtualFile, parts.joinToString("."),
                )
            }
        }

        /** Adapts a live [DasObject] to the pure [DuckdbNamespacePath.Node] walk (carries the node). */
        private class DasNode(val das: DasObject) : DuckdbNamespacePath.Node {
            override val name: String get() = das.name
            override fun childNodes(): List<DuckdbNamespacePath.Node> = children(das).map(::DasNode)
        }

        /**
         * All loaded children of [o] regardless of kind — schemas under a catalog, tables under a
         * schema. Unions the kind-specific views with the all-kinds view (doris belt-and-braces:
         * `getDasChildren(null)` has been observed to skip a subtree in the two-level model).
         */
        private fun children(o: DasObject): List<DasObject> =
            (o.getDasChildren(ObjectKind.SCHEMA).toList() +
                o.getDasChildren(ObjectKind.TABLE).toList() +
                o.getDasChildren(null).toList()).distinct()
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
