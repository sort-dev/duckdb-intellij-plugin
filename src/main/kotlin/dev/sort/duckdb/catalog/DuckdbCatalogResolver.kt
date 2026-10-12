package dev.sort.duckdb.catalog

import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.completion.completableFunctions
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.KeywordCatalog
import dev.brikk.house.sql.metadata.KeywordDef
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.metadata.presentation
import java.util.WeakHashMap

/**
 * Decides which catalog serves an editor: the data source's LIVE harvest when one exists, else
 * the bundled snapshot. Live replaces the bundled registry; grammar forms and unloaded
 * extension offers remain available independently.
 */
object DuckdbCatalogResolver {

    data class ActiveCatalog(
        val functions: List<FunctionPresentation>,
        val keywords: Set<String>,
        /** true = a live harvest is serving this editor's registry. */
        val live: Boolean,
        val dataSourceId: String?,
        val snapshot: MetadataSnapshot,
        val keywordCatalog: KeywordCatalog?,
    )

    private data class CompletionCatalog(
        val functions: List<FunctionPresentation>,
        val keywords: Set<String>,
        val snapshot: MetadataSnapshot,
        val keywordCatalog: KeywordCatalog? = snapshot.keywords,
    )

    private val liveCatalogs = WeakHashMap<DuckdbLiveCatalog.Entry, CompletionCatalog>()
    private val bundledCatalogs by lazy {
        DUCKDB_METADATA.snapshots.associateWith { snapshot ->
            CompletionCatalog(snapshot.completableFunctions(),
                snapshot.keywords?.keywords.orEmpty().mapTo(LinkedHashSet()) { it.word }, snapshot)
        }
    }

    fun activeCatalogFor(file: PsiFile): ActiveCatalog {
        val dataSource = duckdbDataSourceResolver.resolve(file).dataSource
        val dataSourceId = dataSource?.uniqueId
        val live = DuckdbLiveCatalog.entryFor(dataSourceId)
        val catalog = if (live != null) {
            synchronized(liveCatalogs) { liveCatalogs.getOrPut(live) { live.completionCatalog() } }
        } else {
            val snapshot = DUCKDB_METADATA.forServerVersion(dataSource?.databaseVersion?.version).snapshot
            bundledCatalogs.getValue(snapshot)
        }
        return ActiveCatalog(catalog.functions, catalog.keywords, live != null, dataSourceId, catalog.snapshot, catalog.keywordCatalog)
    }

    private fun DuckdbLiveCatalog.Entry.completionCatalog(): CompletionCatalog {
        val snapshot = DUCKDB_METADATA.forServerVersion(engineVersion).snapshot
        val forms = snapshot.grammarForms.orEmpty().associateBy { it.name.lowercase() to it.kind }
        val functions = functions.mapNotNull { it.presentation() }.map { entry ->
            val form = forms[entry.name.lowercase() to entry.kind]
            if (form == null) entry else entry.copy(syntaxes = form.syntaxes, template = form.template)
        }
        val keys = functions.mapTo(HashSet()) { it.name.lowercase() to it.kind }
        val bundled = snapshot.completableFunctions()
        val grammar = bundled.filter { entry ->
            val key = entry.name.lowercase() to entry.kind
            entry.extension == null && key in forms && key !in keys &&
                snapshot.functions.getAll(entry.name).none { it.overloads.isNotEmpty() }
        }
        val names = functions.mapTo(HashSet()) { it.name.lowercase() }
        val extensions = bundled.filter { it.extension != null && it.name.lowercase() !in names }
        return CompletionCatalog(functions + grammar + extensions, keywords, snapshot,
            KeywordCatalog(keywords.map { KeywordDef(it) }))
    }

    private fun DuckdbLiveCatalog.Fn.presentation(): FunctionPresentation? {
        val normalized = when (kind) {
            DuckdbLiveCatalog.Kind.PRAGMA -> return null
            DuckdbLiveCatalog.Kind.AGGREGATE -> FunctionKind.AGGREGATE
            DuckdbLiveCatalog.Kind.WINDOW -> FunctionKind.WINDOW
            DuckdbLiveCatalog.Kind.TABLE, DuckdbLiveCatalog.Kind.TABLE_MACRO -> FunctionKind.TABLE_VALUED
            else -> FunctionKind.SCALAR
        }
        val native = when (kind) {
            DuckdbLiveCatalog.Kind.MACRO -> "macro"
            DuckdbLiveCatalog.Kind.TABLE_MACRO -> "table_macro"
            else -> null
        }
        return FunctionDef(name, kind = normalized, nativeKind = native).presentation()
    }

}
