package dev.sort.duckdb.catalog

import com.intellij.database.DataBus
import com.intellij.database.console.client.SessionClient
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.sort.duckdb.DuckdbDbms

/**
 * Execution observer for the two things a DuckDB console can change that the IDE would otherwise
 * keep showing stale, each with its own detector and its own refresh:
 *
 *  - **`INSTALL` / `FORCE INSTALL` / `LOAD`** ([DuckdbInstallLoadDetector]) -> re-harvest the
 *    source's live FUNCTION catalog so completion picks the new extension's functions up
 *    immediately (the PLAN.md Stage-4 item, and the automated sibling of the manual "Refresh
 *    DuckDB Catalog" action).
 *  - **`ATTACH` / `DETACH`** ([DuckdbAttachDetector]) -> re-list the source's namespaces so a
 *    freshly attached database appears in the object tree (and a detached one leaves). The
 *    platform's own post-execution auto-sync cannot do this — it only refreshes elements already
 *    in the model; see [DuckdbTreeRefresh].
 *
 * ## Seam
 *
 * A project-root [DataAuditor] via [DataBus.addRootAuditor]: every console/grid execution engine
 * publishes its audit events on its session's child message bus, and `DatabaseTopics.AUDIT_TOPIC`
 * broadcasts `TO_PARENT` — so ONE subscription on the project's root data bus observes every
 * session without per-session bookkeeping. [DataAuditor.requestFinished] fires once per completed
 * request (after execution, so a re-harvest sees the newly loaded functions), and its context
 * carries the executed text plus the owner that maps back to the data source.
 *
 * ## What is deliberately NOT observed
 *
 * AUTOLOADED extensions: DuckDB autoload never runs an INSTALL/LOAD statement anywhere this
 * seam could see — the engine loads the extension internally when a query first needs it. The
 * user picks those up with the manual refresh action (user-acknowledged trade; the action's
 * balloon lists loaded extensions for exactly this reason). Likewise `UPDATE EXTENSIONS` does not
 * trigger (repo-metadata only, loads nothing — see [DuckdbInstallLoadDetector]).
 *
 * Multiple triggers in quick succession coalesce into ONE refresh per data source per kind
 * ([DuckdbRefreshDebouncer], ~2s trailing window; the tree branch uses its own key namespace so a
 * script that installs an extension AND attaches through it gets both refreshes). The
 * observer-triggered function refresh posts the same success balloon as the manual action, so the
 * user sees the catalog update happen; the tree refresh is silent — the object tree updating IS
 * the feedback.
 */
// API status: the whole seam is javap-verified free of ApiStatus flags on DataGrip 2026.1.3 —
// DataBus.addRootAuditor, DataAuditor, DataRequest(+Context/Owner/QueryRequest), SessionClient,
// DatabaseSession, DatabaseConnectionPoint (no @Internal/@Experimental/@Deprecated anywhere).
// Rejected alternatives: per-session AuditService (no public attach seam; would need
// DatabaseSessionManagerListener bookkeeping per session), DatabaseSessionStateListener (session
// lifecycle only, no statement text), DataConsumer (results, not statements), and the
// connectionInterceptor (connect-time only). ProjectActivity is @ApiStatus.OverrideOnly (we
// override — the annotation's intent).
class DuckdbInstallLoadObserver(
    private val project: Project,
    private val debouncer: DuckdbRefreshDebouncer = DuckdbRefreshDebouncer(),
    /** Seam for tests; production dispatches the blocking harvest to a pooled thread. */
    private val refresh: (Project, LocalDataSource) -> Unit = { p, ds ->
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = DuckdbCatalogRefresh.harvestNow(p, ds)
            DuckdbCatalogRefresh.notify(p, outcome)
        }
    },
    /**
     * Seam for tests; production re-lists the data source's namespaces after ATTACH/DETACH. The
     * one-shot deepen claims are dropped first: the catalog list just changed, so a namespace that
     * deepened to nothing earlier (or a name now backed by a different database) must be allowed
     * to introspect again.
     */
    private val refreshTree: (Project, LocalDataSource) -> Unit = { p, ds ->
        DuckdbAutoIntrospect.forgetClaims(ds.uniqueId)
        DuckdbTreeRefresh.listNamespaces(p, ds)
    },
) : DataAuditor {

    private companion object {
        val LOG = Logger.getInstance(DuckdbInstallLoadObserver::class.java)

        /** Debounce namespace: ATTACH and INSTALL coalesce independently on the same data source. */
        const val TREE_KEY_PREFIX = "tree:"
    }

    override fun requestFinished(context: DataRequest.Context) {
        // Ordered cheapest-first and never throwing: this runs for EVERY request in the project
        // (any dbms), so non-DuckDB traffic must exit on the dbms gate without touching anything
        // beyond the owner's session — data-source lists are never enumerated here.
        try {
            val dataSource = brikkDataSourceOf(context) ?: return
            val text = executedTextOf(context) ?: return
            dispatch(dataSource, text)
        } catch (t: Throwable) {
            LOG.warn("execution observation failed (ignored): ${t.message}")
        }
    }

    /**
     * The routing decision, factored off [DataRequest.Context] so it is directly unit-testable:
     * which refresh(es) an executed [text] earns on [dataSource]. Both branches are independent —
     * a script may do both (`INSTALL ducklake; ATTACH 'ducklake:…' AS lake`), and each gets its own
     * coalescing window so neither refresh swallows the other.
     */
    internal fun dispatch(dataSource: LocalDataSource, text: String) {
        if (DuckdbInstallLoadDetector.triggersCatalogChange(text)) {
            LOG.info("observed INSTALL/LOAD on '${dataSource.name}'; scheduling debounced catalog refresh")
            debouncer.submit(dataSource.uniqueId) { refresh(project, dataSource) }
        }
        if (DuckdbAttachDetector.triggersNamespaceChange(text)) {
            LOG.info("observed ATTACH/DETACH on '${dataSource.name}'; scheduling debounced tree refresh")
            debouncer.submit(TREE_KEY_PREFIX + dataSource.uniqueId) { refreshTree(project, dataSource) }
        }
    }

    /** Console requests are owned by their session client; gate on OUR dbms before anything else. */
    private fun brikkDataSourceOf(context: DataRequest.Context): LocalDataSource? {
        val session = ((context.request.owner as? SessionClient<*>)?.session as? DatabaseSession) ?: return null
        val point = session.connectionPoint
        if (point.dbms != DuckdbDbms.DUCKDB_BRIKK) return null
        return point.dataSource
    }

    /**
     * The full submitted script when the request is a [DataRequest.QueryRequest] (its `query`
     * field keeps every statement even after the per-statement contexts pop), else the context's
     * current-statement view.
     */
    private fun executedTextOf(context: DataRequest.Context): String? =
        (context.request as? DataRequest.QueryRequest)?.query ?: context.query
}

/**
 * Project service owning the root-auditor subscription's lifetime (its [Disposable] detaches the
 * message-bus connection on project close / plugin unload). Instantiated lazily by
 * [DuckdbInstallLoadStartup]; holds no state and does nothing until an audit event arrives.
 */
@Service(Service.Level.PROJECT)
class DuckdbInstallLoadObserverService(project: Project) : Disposable {
    init {
        DataBus.addRootAuditor(project, DuckdbInstallLoadObserver(project), this)
    }

    override fun dispose() = Unit
}

/** `<postStartupActivity>`: subscribe the observer once the project is up (zero further cost). */
class DuckdbInstallLoadStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<DuckdbInstallLoadObserverService>()
    }
}
