package dev.sort.duckdb.brikk

import dev.brikk.house.sql.intellij.host.HostSqlChannel
import dev.brikk.house.sql.intellij.host.HostSqlPlaceholderErrorFilter
import dev.brikk.house.sql.intellij.host.HostSqlRunConsoleVeto
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiLanguageInjectionHost

interface DuckdbInspectionHost {
    fun claims(host: PsiLanguageInjectionHost): Boolean
    companion object {
        val EP = ExtensionPointName.create<DuckdbInspectionHost>("dev.sort.duckdb.inspectionHost")
    }
}

object DuckdbHostSql : HostSqlChannel("dev.sort.duckdb") {
    override fun claimsHost(host: PsiLanguageInjectionHost) = DuckdbInspectionHost.EP.extensionList.any { it.claims(host) }
}

class DuckdbPlaceholderErrorFilter : HostSqlPlaceholderErrorFilter(DuckdbHostSql)

class DuckdbRunConsoleVeto : HostSqlRunConsoleVeto(DuckdbHostSql)
