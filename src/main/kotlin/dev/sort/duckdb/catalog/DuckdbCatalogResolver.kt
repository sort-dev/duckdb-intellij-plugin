package dev.sort.duckdb.catalog

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.completion.completableFunctions
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.metadata.presentation
import dev.sort.duckdb.DuckdbDbms

/**
 * Decides which catalog serves an editor: the data source's LIVE harvest when one exists, else
 * the bundled snapshot. Live REPLACES bundled entirely — never a merge: a connected 1.3 engine
 * must not be offered 1.5.5-only names from our snapshot, and version-exactness cuts both ways.
 *
 * Data-source resolution, public seams only:
 *  1. a database console resolves through [JdbcConsoleProvider.getValidConsole] (its own source);
 *  2. any other file falls back to "the project has exactly one DuckDB (Brikk) data source" —
 *     ambiguous setups (0 or 2+) stay on the bundled snapshot rather than guessing.
 */
object DuckdbCatalogResolver {

    data class ActiveCatalog(
        val functions: List<FunctionPresentation>,
        val keywords: Set<String>,
        /** true = a live harvest is serving this editor (bundled fully replaced). */
        val live: Boolean,
        val dataSourceId: String?,
        val snapshot: MetadataSnapshot,
    )

    fun activeCatalogFor(file: PsiFile): ActiveCatalog {
        val dataSource = dataSourceFor(file)
        val dataSourceId = dataSource?.uniqueId
        val live = DuckdbLiveCatalog.entryFor(dataSourceId)
        val snapshot = DUCKDB_METADATA.forServerVersion(live?.engineVersion ?: dataSource?.databaseVersion?.version).snapshot
        return if (live != null) {
            val functions = live.functions.mapNotNull { it.presentation() }
            val names = functions.mapTo(HashSet()) { it.name.lowercase() }
            val extensions = snapshot.completableFunctions().filter { it.extension != null && it.name.lowercase() !in names }
            ActiveCatalog(functions + extensions, live.keywords, live = true, dataSourceId = dataSourceId, snapshot = snapshot)
        } else {
            ActiveCatalog(snapshot.completableFunctions(), snapshot.keywords?.keywords.orEmpty().mapTo(LinkedHashSet()) { it.word }, live = false,
                dataSourceId = dataSourceId, snapshot = snapshot)
        }
    }

    private fun DuckdbLiveCatalog.Fn.presentation(): FunctionPresentation? {
        val normalized = when (kind) {
            DuckdbLiveCatalog.Kind.PRAGMA -> return null
            DuckdbLiveCatalog.Kind.AGGREGATE -> FunctionKind.AGGREGATE
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

    /** Everything best-effort: any surprise resolves to `null` -> bundled snapshot. */
    fun dataSourceIdFor(file: PsiFile): String? = dataSourceFor(file)?.uniqueId

    /** Same resolution, keeping the [LocalDataSource] itself (the refresh action's target). */
    fun dataSourceFor(file: PsiFile): LocalDataSource? {
        val project = file.project
        val virtualFile = file.originalFile.virtualFile
        if (virtualFile != null) {
            val console = recoverEditorOperation { JdbcConsoleProvider.getValidConsole(project, virtualFile) }.getOrNull()
            val dataSource = console?.dataSource
            if (dataSource != null && dataSource.dbms == DuckdbDbms.DUCKDB_BRIKK) return dataSource
        }
        return singleDataSource(project)
    }

    /** The project's single DuckDB (Brikk) data source; 0 or 2+ resolve to `null` (no guessing). */
    fun singleDataSource(project: Project): LocalDataSource? {
        val ours = recoverEditorOperation {
            LocalDataSourceManager.getInstance(project).dataSources.filter { it.dbms == DuckdbDbms.DUCKDB_BRIKK }
        }.getOrDefault(emptyList())
        return ours.singleOrNull()
    }
}
