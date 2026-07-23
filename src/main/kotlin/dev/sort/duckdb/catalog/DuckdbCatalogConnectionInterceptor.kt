package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.openapi.diagnostic.Logger
import dev.sort.duckdb.DuckdbDbms

/**
 * `<database.connectionInterceptor>` — the on-connect harvester behind the live catalog
 * (PLAN.md Stage 4, "connected" source).
 *
 * Platform seam (same one the sibling doris plugin ships on): every EP interceptor's
 * [interceptConnection] runs while the proto-connection is prepared; only interceptors returning
 * `true` join the proto's active list, and once the JDBC connection is established the
 * establisher calls [handleConnected] on each. Our override doubles as the dbms guard — non-Brikk
 * data sources never see [handleConnected] at all.
 *
 * On each new DuckDB (Brikk) connection the four catalog queries ([DuckdbCatalogHarvester]) run
 * over the fresh connection and the result REPLACES that data source's cached catalog
 * ([DuckdbLiveCatalog.store]). Entirely fail-soft: any failure (or a blown deadline) is logged,
 * the previous cache stays, and the user's connect proceeds untouched.
 */
// API status: DatabaseConnectionInterceptor is class-level @ApiStatus.Experimental (DB-261). It is
// the only seam that observes every new connection (consoles and introspection alike) with the
// connection handed to us before user statements run; the doris plugin ships on the same suspend
// overrides. The deprecated+scheduled-for-removal intercept() stage is deliberately NOT overridden
// in source; the Kotlin compiler still emits delegating stubs for ALL of the interface's default
// methods (intercept, handleConnectionFailure, handleNullConnection), so the plugin verifier
// reports 2 "deprecated intercept() overridden/invoked" usages against this class. Compiler
// artifact, not a call we make — same carried warning as the sibling doris plugin since 0.5.0.
class DuckdbCatalogConnectionInterceptor : DatabaseConnectionInterceptor {

    private companion object {
        val LOG = Logger.getInstance(DuckdbCatalogConnectionInterceptor::class.java)
    }

    override suspend fun interceptConnection(
        proto: DatabaseConnectionInterceptor.ProtoConnection,
        silent: Boolean,
    ): Boolean = proto.connectionPoint.dbms == DuckdbDbms.DUCKDB_BRIKK

    override suspend fun handleConnected(
        connection: DatabaseConnectionCore,
        proto: DatabaseConnectionInterceptor.ProtoConnection,
    ) {
        // interceptConnection already gated on dbms; re-check defensively (cheap).
        if (connection.dbms != DuckdbDbms.DUCKDB_BRIKK) return
        val dataSource = connection.connectionPoint.dataSource
        try {
            val entry = DuckdbCatalogHarvester.harvest(RemoteRowSource(connection))
            if (entry == null) {
                LOG.info("live-catalog harvest for '${dataSource.name}' abandoned; keeping previous catalog")
                return
            }
            DuckdbLiveCatalog.store(dataSource.uniqueId, entry)
            LOG.info(
                "live catalog for '${dataSource.name}' (${entry.engineVersion}): " +
                    "${entry.functions.size} functions, ${entry.keywords.size} keywords, " +
                    "extensions=${entry.loadedExtensions}",
            )
        } catch (t: Throwable) {
            // Never break the user's connect; the bundled snapshot / previous harvest still serves.
            LOG.warn("live-catalog harvest for '${dataSource.name}' failed; keeping previous catalog: ${t.message}")
        }
    }

    /**
     * [CatalogRowSource] over the platform's out-of-process JDBC: statement lifecycle through
     * [JdbcNativeUtil] (the doris interceptor's proven execute shape), row reads as direct
     * `RemoteResultSet` calls — any exception propagates to the fail-soft catch above.
     */
    private class RemoteRowSource(private val connection: DatabaseConnectionCore) : CatalogRowSource {
        override fun forEachRow(sql: String, onRow: (col: (Int) -> String?) -> Unit) {
            val statement = JdbcNativeUtil.computeRemote {
                connection.remoteConnection.createStatement()
            } ?: throw IllegalStateException("could not create statement")
            try {
                // Per-query guard under the harvest's wall-clock deadline; drivers without
                // timeout support (quack) just skip it.
                JdbcNativeUtil.performSafe { statement.setQueryTimeout(3) }
                val resultSet = JdbcNativeUtil.computeRemote { statement.executeQuery(sql) } ?: return
                try {
                    while (resultSet.next()) {
                        onRow { i -> resultSet.getString(i) }
                    }
                } finally {
                    JdbcNativeUtil.performSafe { resultSet.close() }
                }
            } finally {
                JdbcNativeUtil.closeRemoteStatementSafe(statement)
            }
        }
    }
}
