package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.LocalDataSource

/** The resolver's SQL-dialect aliases are not a license to show options on unrelated drivers. */
internal object DuckdbGrammarSources {
    fun accepts(source: LocalDataSource): Boolean {
        if (source.dbms.name !in setOf("DUCKDB_BRIKK", "DUCKDB", "DUCK")) return false
        val driver = source.driverClass ?: source.databaseDriver?.driverClass
        return when (driver) {
            "org.duckdb.DuckDBDriver" -> source.url.isNullOrBlank() || source.url!!.startsWith("jdbc:duckdb:")
            "com.gizmodata.quack.jdbc.sql.QuackDriver" -> source.url.isNullOrBlank() || source.url!!.startsWith("jdbc:quack:")
            else -> false
        }
    }
}
