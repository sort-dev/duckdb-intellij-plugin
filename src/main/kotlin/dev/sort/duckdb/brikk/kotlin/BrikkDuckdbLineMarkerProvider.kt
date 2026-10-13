package dev.sort.duckdb.brikk.kotlin

import com.intellij.openapi.project.DumbService
import dev.brikk.house.sql.intellij.completion.SqlLookupIcons
import dev.brikk.house.sql.intellij.host.HostSqlClaim
import dev.brikk.house.sql.intellij.host.HostSqlLineMarkerProvider
import dev.brikk.house.sql.intellij.host.HostSqlState
import dev.sort.duckdb.brikk.DuckdbHostData
import dev.sort.duckdb.brikk.DuckdbHostSql

class BrikkDuckdbLineMarkerProvider : HostSqlLineMarkerProvider(DuckdbHostSql, "DuckDB Brikk SQL", SqlLookupIcons.BrikkColumn) {
    override fun tooltip(state: HostSqlState): String {
        val claim = state.claim as? HostSqlClaim.Injected
        val project = (claim?.data as? DuckdbHostData)?.project
        val waiting = claim == null || claim.pending || project?.let(DumbService::isDumb) == true
        return "DuckDB Brikk SQL: PIPE editing, named binds and relation contracts; inspection only, not executable" +
            if (waiting) "; waiting for indexing" else ""
    }
}
