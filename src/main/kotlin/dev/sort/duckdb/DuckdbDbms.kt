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
    private val icon = IconLoader.getIcon("/icons/duckdb-brikk.svg", DuckdbDbms::class.java)

    @JvmField
    val DUCKDB_BRIKK: Dbms = Dbms.create(
        "DUCKDB_BRIKK",
        "DuckDB (Brikk)",
        { icon },
        Dbms.defaultPattern("duckdb")
    )
}
