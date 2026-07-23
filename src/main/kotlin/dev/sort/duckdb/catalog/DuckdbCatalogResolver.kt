package dev.sort.duckdb.catalog

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.sql.DuckdbFunctionCatalog

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
        val functions: List<DuckdbFunctionCatalog.Fn>,
        val keywords: Set<String>,
        /** true = a live harvest is serving this editor (bundled fully replaced). */
        val live: Boolean,
        val dataSourceId: String?,
    )

    fun activeCatalogFor(file: PsiFile): ActiveCatalog {
        val dataSourceId = dataSourceIdFor(file)
        val live = DuckdbLiveCatalog.entryFor(dataSourceId)
        return if (live != null) {
            ActiveCatalog(live.functions, live.keywords, live = true, dataSourceId = dataSourceId)
        } else {
            ActiveCatalog(DuckdbFunctionCatalog.functions, DuckdbFunctionCatalog.keywords, live = false, dataSourceId = dataSourceId)
        }
    }

    /** Everything best-effort: any surprise resolves to `null` -> bundled snapshot. */
    fun dataSourceIdFor(file: PsiFile): String? = dataSourceFor(file)?.uniqueId

    /** Same resolution, keeping the [LocalDataSource] itself (the refresh action's target). */
    fun dataSourceFor(file: PsiFile): LocalDataSource? {
        val project = file.project
        val virtualFile = file.originalFile.virtualFile
        if (virtualFile != null) {
            val console = runCatching { JdbcConsoleProvider.getValidConsole(project, virtualFile) }.getOrNull()
            val dataSource = console?.dataSource
            if (dataSource != null && dataSource.dbms == DuckdbDbms.DUCKDB_BRIKK) return dataSource
        }
        return singleDataSource(project)
    }

    /** The project's single DuckDB (Brikk) data source; 0 or 2+ resolve to `null` (no guessing). */
    fun singleDataSource(project: Project): LocalDataSource? {
        val ours = runCatching {
            LocalDataSourceManager.getInstance(project).dataSources.filter { it.dbms == DuckdbDbms.DUCKDB_BRIKK }
        }.getOrDefault(emptyList())
        return ours.singleOrNull()
    }
}
