package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.util.LoaderContext
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternUtils
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.sort.duckdb.catalog.DuckdbNamespacePath.Level
import org.jetbrains.annotations.TestOnly

/**
 * LAZY, TARGETED introspection for DuckDB (Brikk) — ported from the sibling trino plugin (itself
 * ported from doris's production `DorisPipesAutoIntrospect`). When the user path-types into an
 * enumerated-but-childless namespace (an ATTACHed catalog whose schemas were never loaded; a schema
 * whose tables were never loaded), don't make them go click "introspect": widen the data source's
 * `introspectionScope` to EXACTLY that node (union never clobbers the user's own selections) and
 * kick a TARGETED one-element refresh. The platform's own completion then shows the freshly-loaded
 * names on the next invoke.
 *
 * ## Why DuckDB needs this at all
 *
 * A single local `.duckdb` file introspects fast enough that nobody would miss lazy loading — but
 * `ATTACH` makes the catalog list open-ended, and `ATTACH 'ducklake:…'` / `'postgres:…'` /
 * `'mysql:…'` put arbitrarily large remote databases behind one keystroke. Paired with
 * [DuckdbTreeRefresh] (which brings a freshly ATTACHed catalog INTO the tree), this is what fills
 * it in — cheaply, one level at a time, only where the user is actually looking.
 *
 * Two rules keep it safe:
 *  - **One level at a time.** A catalog deepening loads only that catalog's schemas; a schema
 *    deepening only that schema's tables ([DuckdbIntrospectionTasks.oneElementRefresh]).
 *  - **One shot per (data source, namespace) per IDE session.** A failed or empty introspection
 *    never loops — [claimOnce] dedupes on a stable key.
 *
 * ## API status
 *
 * Per-symbol, javap-verified on DataGrip 2026.1.3 and cross-checked against an EMPTY
 * internal-api-usages verifier report on both 261 and 262:
 *  - [LoaderContext] carries NO class-level flag. Only `selectSkip` is `@ApiStatus.Internal` and
 *    only `selectNothing` is `@Deprecated`; `selectTask` (used here) is plain public API. The
 *    sibling doris/trino sources claim the whole class is internal — that claim is wrong on this
 *    platform build; don't propagate it.
 *  - [DataSourceSyncManager].tryPerform is Kotlin-`@Deprecated` ("use coroutines") — the only new
 *    verifier item this feature adds. The coroutine replacement is a suspend fun and this
 *    fail-soft, fire-and-forget completion path is not a coroutine context, so the stable
 *    non-suspend entry point is the correct one.
 *  - `IntrospectionTasks` is Kotlin-`internal` in metadata (public bytecode), reached through the
 *    [DuckdbIntrospectionTasks] Java shim.
 *  - [TreePattern]/[TreePatternUtils]/`TreePatternNode`/`ObjectName`/`ObjectKind` and
 *    [LocalDataSource.setIntrospectionScope] carry NO ApiStatus flags.
 */
object DuckdbAutoIntrospect {

    private val LOG = Logger.getInstance(DuckdbAutoIntrospect::class.java)

    /** One-shot guard: `uniqueId|LEVEL:catalog[.schema]` already requested this IDE session. */
    private val requested = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Stable dedupe key for a deepening (factored for direct unit testing). */
    internal fun keyFor(uniqueId: String, level: Level, catalog: String, schema: String?): String =
        when (level) {
            Level.CATALOG -> "$uniqueId|CATALOG:$catalog"
            Level.SCHEMA -> "$uniqueId|SCHEMA:$catalog.$schema"
        }

    /** True the FIRST time [key] is seen; false forever after (per IDE session). Unit-testable. */
    internal fun claimOnce(key: String): Boolean = requested.add(key)

    /** The scope addition for a deepening (pure — delegates to [DuckdbCatalogScopes]). */
    internal fun scopeFor(level: Level, catalog: String, schema: String?): TreePattern = when (level) {
        Level.CATALOG -> DuckdbCatalogScopes.catalogSchemasScope(catalog)
        Level.SCHEMA -> DuckdbCatalogScopes.schemaTablesScope(catalog, schema!!)
    }

    /**
     * Kick a targeted introspection deepening of [catalog] (→ schemas) or [catalog].[schema]
     * (→ tables) on [local]. Returns true iff a NEW introspection was started; false when it was
     * already requested this session or anything went wrong (always fail-soft — a completion pass
     * must never throw or block).
     *
     * [node] must be the resolved model element (a [BasicElement]) for the one-element refresh; if
     * it is not (defensive), the scope is still widened so a later manual refresh honours it, but
     * no sync is kicked.
     */
    fun request(
        project: Project,
        local: LocalDataSource,
        level: Level,
        catalog: String,
        schema: String?,
        node: Any?,
    ): Boolean {
        val key = keyFor(local.uniqueId, level, catalog, schema)
        if (!claimOnce(key)) return false
        return runCatching {
            // Union, never replace: the data source's existing scope (the platform's `@:@` default
            // on a fresh source, or whatever the user picked in Schemas) must survive untouched —
            // measured working end to end by DuckdbAttachTreeLiveTest. `introspectionScope` is
            // declared non-null by the platform, so there is no null case to branch on.
            local.introspectionScope =
                TreePatternUtils.union(local.introspectionScope, scopeFor(level, catalog, schema))

            val element = node as? BasicElement
            if (element == null) {
                LOG.info("auto-introspect: scope widened for $key; node is not a BasicElement — no refresh kicked")
                return@runCatching false
            }
            val task = DuckdbIntrospectionTasks.oneElementRefresh(local.uniqueId, element)
            DataSourceSyncManager.getInstance()
                .tryPerform(LoaderContext.selectTask(project, local, task), true, false)
            LOG.info("auto-introspect: scope widened + TARGETED one-element refresh for $key")
            true
        }.getOrElse { t ->
            LOG.warn("auto-introspect failed for $key: ${t.message}", t)
            false
        }
    }

    /**
     * Forget every one-shot claim for [dataSourceId] — called after an `ATTACH`/`DETACH` tree
     * refresh, because the catalog list just changed: a namespace that introspected to nothing
     * before the ATTACH (or a name reused by a different database) must be allowed to deepen again.
     */
    fun forgetClaims(dataSourceId: String) {
        synchronized(requested) { requested.removeIf { it.startsWith("$dataSourceId|") } }
    }

    /** Test hook: forget every one-shot claim so a case can re-request in a fresh scenario. */
    @TestOnly
    fun resetForTest() {
        requested.clear()
    }
}
