package dev.sort.duckdb.brikk.kotlin

import com.intellij.psi.PsiLanguageInjectionHost
import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkHosts
import dev.brikk.house.intellij.BrikkHostState
import dev.brikk.house.sql.intellij.host.HostSqlClaim
import dev.brikk.house.sql.intellij.host.HostSqlInjector
import dev.sort.duckdb.brikk.DuckdbHostSql
import dev.sort.duckdb.brikk.DuckdbHostData
import dev.sort.duckdb.sql.DuckdbSqlDialect
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkDuckdbInjector : HostSqlInjector(DuckdbHostSql, DuckdbSqlDialect) {
    override val prefix = "-- dialect: duckdb\n"

    override fun claim(host: PsiLanguageInjectionHost): HostSqlClaim {
        if (host !is KtStringTemplateExpression) return HostSqlClaim.NotClaimed
        val state = BrikkHosts.state(host)
        if (state.dialect != BrikkDialect.DUCKDB) return HostSqlClaim.NotClaimed
        val context = BrikkDuckdbKotlinContext.find(host)
        if (context != null) {
            val template = BrikkDuckdbKotlinContext.template(context)
            return HostSqlClaim.Injected(DuckdbSqlDialect, template.entries, pending = context.pending,
                inspectionOnly = true, colonBinds = true, data = DuckdbHostData(template.profile, host.project))
        }
        return if (state is BrikkHostState.Pending) HostSqlClaim.Unavailable(state.reason) else HostSqlClaim.NotClaimed
    }
}
