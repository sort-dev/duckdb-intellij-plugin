package dev.sort.duckdb.sql

import com.intellij.database.Dbms
import dev.brikk.house.sql.intellij.dialect.PgFamilyDialect
import dev.sort.duckdb.DuckdbDbms

object DuckdbSqlDialect : PgFamilyDialect("DuckDBSQL") {
    override fun getDbms(): Dbms = DuckdbDbms.DUCKDB_BRIKK
}
