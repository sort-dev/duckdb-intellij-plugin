package dev.sort.duckdb.sql

import com.intellij.database.Dbms
import dev.brikk.house.sql.intellij.dialect.EngineBuiltins
import dev.brikk.house.sql.intellij.dialect.PgFamilyDialect
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.brikk.DuckdbRelationProfile

object DuckdbSqlDialect : PgFamilyDialect("DuckDBSQL") {
    override val builtins by lazy { EngineBuiltins(DuckdbCompletionProfile, DuckdbRelationProfile) }
    override fun getDbms(): Dbms = DuckdbDbms.DUCKDB_BRIKK
}
