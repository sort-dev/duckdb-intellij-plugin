package dev.sort.duckdb

import com.intellij.database.Dbms
import com.intellij.openapi.util.IconLoader

/**
 * The "DuckDB (Brikk)" dbms — minted ALONGSIDE the platform's stock DuckDB registration
 * (GenericDbms.DUCKDB + GenericSQL + a driver template), never overriding it: stock data sources
 * keep behaving stock, and a future first-party DuckDB dialect from JetBrains cannot collide with
 * us. Same coexistence strategy the Doris plugin uses (and StarRocks before it).
 */
object DuckdbDbms {
    // Official DuckDB mark (duckdb.org/design/manual, used unmodified); IconLoader picks
    // icons/duckdb_dark.svg automatically in dark themes by naming convention.
    private val icon = IconLoader.getIcon("/icons/duckdb.svg", DuckdbDbms::class.java)

    @JvmField
    val DUCKDB_BRIKK: Dbms = Dbms.create(
        "DUCKDB_BRIKK",
        // User-facing dialect label (the SQL-dialect picker); "sort.dev" is the vendor. The
        // internal id stays DUCKDB_BRIKK / DuckDBBrikk (code + existing file→dialect mappings).
        "DuckDB (sort.dev)",
        { icon },
        Dbms.defaultPattern("duckdb")
    )
}
