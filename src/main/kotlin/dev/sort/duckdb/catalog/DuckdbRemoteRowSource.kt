package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil

/**
 * [CatalogRowSource] over the platform's out-of-process JDBC: statement lifecycle through
 * [JdbcNativeUtil] (the doris interceptor's proven execute shape), row reads as direct
 * `RemoteResultSet` calls — any exception propagates to the caller's fail-soft catch.
 *
 * Shared by the two IDE-side harvest triggers: the on-connect interceptor
 * ([DuckdbCatalogConnectionInterceptor], over the fresh console/introspection connection) and the
 * on-demand refresh ([DuckdbCatalogRefresh], over a plugin-owned helper connection —
 * `DatabaseConnection extends DatabaseConnectionCore`, so both hand in the same type).
 */
internal class DuckdbRemoteRowSource(private val connection: DatabaseConnectionCore) : CatalogRowSource {
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
