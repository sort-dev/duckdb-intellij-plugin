package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.util.LoaderContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Re-list a data source's namespaces (catalogs + schemas) without a deep introspection — the
 * object-tree half of the execution observer, kicked when [DuckdbAttachDetector] sees an
 * `ATTACH` / `DETACH` go by.
 *
 * ## Why "list namespaces" and not a general refresh
 *
 * `ATTACH` changes exactly one thing: which catalogs exist. `LoaderContext.selectListNamespacesTask`
 * is the platform's own cheap enumeration pass — the same shape the IDE runs on first connect —
 * so a `ATTACH 'ducklake:…'` over a slow remote does not turn into a full table-and-column crawl
 * of the new catalog. The new catalog lands in the tree; its contents fill in on demand via
 * [DuckdbAutoIntrospect] when the user path-types into it (or with an explicit refresh).
 *
 * Fail-soft by construction: this is fired from an audit callback, so it must never throw and
 * never block. `tryPerform` returns immediately with an async task we deliberately drop.
 */
object DuckdbTreeRefresh {

    private val LOG = Logger.getInstance(DuckdbTreeRefresh::class.java)

    /**
     * Kick a namespace re-listing on [dataSource]. Returns true iff the sync was submitted.
     */
    // API status, javap-verified per symbol on DataGrip 2026.1.3 (and confirmed by an empty
    // internal-api-usages report from the plugin verifier on both 261 and 262):
    //  - LoaderContext carries NO class-level flag. Only `selectSkip` is @ApiStatus.Internal and
    //    only `selectNothing` is @Deprecated; `selectListNamespacesTask` (used here) is plain
    //    public API. Do NOT copy the sibling doris/trino claim that the whole class is internal.
    //  - DataSourceSyncManager.tryPerform is Kotlin-@Deprecated ("use coroutines") — the sole new
    //    verifier item this feature adds. The coroutine replacement `tryPerformSync` is a suspend
    //    fun; this fire-and-forget path runs on a pooled debounce thread with no coroutine context,
    //    so the stable non-suspend entry point is the correct one.
    fun listNamespaces(project: Project, dataSource: LocalDataSource): Boolean = runCatching {
        val context = LoaderContext.selectListNamespacesTask(project, dataSource)
        DataSourceSyncManager.getInstance().tryPerform(context, true, false)
        LOG.info("tree refresh: re-listing namespaces of '${dataSource.name}' after ATTACH/DETACH")
        true
    }.getOrElse { t ->
        LOG.warn("tree refresh for '${dataSource.name}' failed (ignored): ${t.message}", t)
        false
    }
}
