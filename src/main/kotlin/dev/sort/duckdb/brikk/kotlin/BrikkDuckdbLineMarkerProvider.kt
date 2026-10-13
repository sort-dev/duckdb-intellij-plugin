package dev.sort.duckdb.brikk.kotlin

import com.intellij.openapi.project.DumbService
import dev.brikk.house.intellij.BrikkPendingCause
import dev.brikk.house.sql.intellij.completion.SqlLookupIcons
import dev.brikk.house.sql.intellij.host.HostSqlClaim
import dev.brikk.house.sql.intellij.host.HostSqlLineMarkerProvider
import dev.brikk.house.sql.intellij.host.HostSqlState
import dev.sort.duckdb.brikk.DuckdbHostData
import dev.sort.duckdb.brikk.DuckdbHostSql

class BrikkDuckdbLineMarkerProvider : HostSqlLineMarkerProvider(DuckdbHostSql, "DuckDB Brikk SQL", SqlLookupIcons.BrikkColumn) {
    override fun tooltip(state: HostSqlState): String {
        val claim = state.claim as? HostSqlClaim.Injected
        val data = claim?.data as? DuckdbHostData
        val cause = if (data?.project?.let(DumbService::isDumb) == true) BrikkPendingCause.INDEXING else data?.pendingCause
        val status = cause?.let(::pendingStatus) ?: if (claim == null || claim.pending) "waiting for recognition" else null
        return "DuckDB Brikk SQL: PIPE editing, named binds and relation contracts; inspection only, not executable" +
            (status?.let { "; $it" } ?: "")
    }
}

internal fun pendingStatus(cause: BrikkPendingCause): String = when (cause) {
    BrikkPendingCause.INDEXING -> "waiting for indexing"
    BrikkPendingCause.ANALYSIS_PROHIBITED -> "waiting for analysis"
    BrikkPendingCause.UNRESOLVED -> "waiting for resolution"
    BrikkPendingCause.RUNTIME_ENTRY_MISSING -> "Brikk runtime entry is missing"
    BrikkPendingCause.GENERATED_SHAPES -> "waiting for generated relation shapes"
    BrikkPendingCause.COMPILER_PLUGIN_MISSING -> "Brikk compiler plugin is missing"
}
