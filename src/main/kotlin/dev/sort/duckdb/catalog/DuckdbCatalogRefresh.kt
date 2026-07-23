package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * On-demand live-catalog refresh — the shared core behind the manual "Refresh DuckDB Catalog"
 * action and the INSTALL/LOAD execution observer ([DuckdbInstallLoadObserver]).
 *
 * Layering (testability): [harvestAndStore] is the whole refresh minus the IDE — it runs the
 * existing [DuckdbCatalogHarvester] over any [CatalogRowSource] and updates [DuckdbLiveCatalog]
 * (memory + disk), so tests drive it with the in-process engine. [harvestNow] is the thin
 * IDE-bound layer on top: it acquires a plugin-owned helper connection and hands the remote row
 * source down. Message texts are pure functions; only [notify] touches the notification service.
 *
 * The manual action is also how users pick up AUTOLOADED extensions: autoload never runs an
 * INSTALL/LOAD statement the observer could see, so the success balloon always lists the loaded
 * extensions — the user's confirmation that e.g. an autoloaded `spatial` actually arrived.
 */
object DuckdbCatalogRefresh {

    /** Balloon group id — registered as a `notificationGroup` EP in plugin.xml. */
    const val NOTIFICATION_GROUP_ID = "DuckDB (Brikk)"

    private val LOG = Logger.getInstance(DuckdbCatalogRefresh::class.java)

    sealed interface Outcome {
        data class Refreshed(val entry: DuckdbLiveCatalog.Entry) : Outcome

        /** No DuckDB (Brikk) data source resolvable for the invocation context. */
        data object NoDataSource : Outcome

        /** Could not open the helper connection (auth, server down, driver missing...). */
        data class ConnectFailed(val detail: String?) : Outcome

        /** Connected, but the harvest abandoned (query error / empty inventory / deadline). */
        data object HarvestFailed : Outcome
    }

    /**
     * The IDE-free refresh: harvest over [rows], REPLACE the cached catalog for [dataSourceId] on
     * success. Any surprise (including exceptions out of [rows]) maps to [Outcome.HarvestFailed]
     * and the previous cache stays — same fail-soft bar as the on-connect interceptor.
     */
    fun harvestAndStore(dataSourceId: String, rows: CatalogRowSource): Outcome {
        val entry = try {
            DuckdbCatalogHarvester.harvest(rows)
        } catch (t: Throwable) {
            LOG.warn("catalog refresh for $dataSourceId failed; keeping previous catalog: ${t.message}")
            return Outcome.HarvestFailed
        } ?: return Outcome.HarvestFailed
        DuckdbLiveCatalog.store(dataSourceId, entry)
        return Outcome.Refreshed(entry)
    }

    /**
     * Full refresh against [dataSource]: open a plugin-owned helper connection, harvest, store.
     * BLOCKS on the connect — call from a pooled thread, never the EDT (both call sites dispatch
     * via `executeOnPooledThread`).
     *
     * Connection shape is the doris cancel action's proven helper pattern:
     * `DatabaseConnectionManager.build(project, point).setRequestor(Anonymous()).createBlockingNonCancellable()`
     * in a `GuardedRef.use` so the connection is always released. The connection point IS the
     * data source — `LocalDataSource implements DatabaseConnectionConfig extends
     * DatabaseConnectionPoint` (the platform's own DataSourceSyncManager passes a LocalDataSource
     * where its introspector session template is absent).
     */
    // API status: DatabaseConnectionManager.getInstance/build, Builder.setRequestor/
    // createBlockingNonCancellable, ConnectionRequestor.Anonymous, GuardedRef — all javap-verified
    // free of ApiStatus flags on DataGrip 2026.1.3 (no @Internal/@Experimental/@Deprecated).
    fun harvestNow(project: Project, dataSource: LocalDataSource): Outcome {
        val ref = try {
            DatabaseConnectionManager.getInstance()
                .build(project, dataSource)
                .setRequestor(ConnectionRequestor.Anonymous())
                .createBlockingNonCancellable()
        } catch (t: Throwable) {
            LOG.warn("catalog refresh for '${dataSource.name}': helper connection failed: ${t.message}")
            return Outcome.ConnectFailed(t.message)
        } ?: return Outcome.ConnectFailed(null)
        return ref.use { r ->
            val outcome = harvestAndStore(dataSource.uniqueId, DuckdbRemoteRowSource(r.get()))
            if (outcome is Outcome.Refreshed) {
                LOG.info(
                    "catalog refreshed for '${dataSource.name}' (${outcome.entry.engineVersion}): " +
                        "${outcome.entry.functions.size} functions, extensions=${outcome.entry.loadedExtensions}",
                )
            }
            outcome
        }
    }

    // ---------------------------------------------------------------------------------------
    // User-facing texts (pure — unit-tested) + the one notification side effect
    // ---------------------------------------------------------------------------------------

    fun successText(entry: DuckdbLiveCatalog.Entry): String {
        val extensions =
            if (entry.loadedExtensions.isEmpty()) "none beyond core"
            else entry.loadedExtensions.joinToString(", ")
        return "Catalog refreshed: DuckDB ${entry.engineVersion}, " +
            "${entry.functions.size} functions, extensions: $extensions"
    }

    fun failureText(outcome: Outcome): String = when (outcome) {
        is Outcome.Refreshed -> error("not a failure")
        Outcome.NoDataSource -> "No DuckDB (Brikk) data source to refresh."
        is Outcome.ConnectFailed ->
            "Could not connect to the data source" +
                (outcome.detail?.takeIf { it.isNotBlank() }?.let { ": ${it.take(200)}" } ?: ".")
        Outcome.HarvestFailed -> "Connected, but reading the catalog failed; keeping the previous one."
    }

    /** Balloon for [outcome] — success shows the loaded-extension list (autoload visibility). */
    fun notify(project: Project, outcome: Outcome) {
        val (type, text) = when (outcome) {
            is Outcome.Refreshed -> NotificationType.INFORMATION to successText(outcome.entry)
            else -> NotificationType.WARNING to failureText(outcome)
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification("DuckDB catalog", text, type)
            .notify(project)
    }
}
