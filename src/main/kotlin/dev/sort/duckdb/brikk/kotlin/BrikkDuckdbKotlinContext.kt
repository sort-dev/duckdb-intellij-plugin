package dev.sort.duckdb.brikk.kotlin

import com.intellij.openapi.util.TextRange
import dev.brikk.house.intellij.*
import dev.brikk.house.sql.intellij.host.HostSqlEntries
import dev.brikk.house.sql.intellij.host.HostSqlEntry
import dev.brikk.house.sql.intellij.host.HostSqlTrim
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Dialect projection only. All Brikk symbol/entry semantics live in the shared helper. */
internal object BrikkDuckdbKotlinContext {
    data class Context(val host: KtStringTemplateExpression, val shared: BrikkContext, val pending: Boolean = false,
                       val cause: BrikkPendingCause? = null)
    data class Template(val context: Context, val entries: HostSqlEntries, val profile: BrikkDuckdbProfile,
                        val diagnostics: List<Pair<TextRange, String>>)

    fun find(host: KtStringTemplateExpression): Context? = when (val state = BrikkHosts.state(host)) {
        is BrikkHostState.Recognized -> state.context.takeIf { it.dialect == BrikkDialect.DUCKDB }?.let { Context(host, it) }
        is BrikkHostState.Pending -> state.projection?.takeIf { it.dialect == BrikkDialect.DUCKDB }?.let { Context(host, it, pending = true, cause = state.cause) }
        BrikkHostState.NotBrikk -> null
    }

    fun template(context: Context): Template {
        val entries = context.shared.entries
        val diagnostics = if (context.pending) emptyList() else entries.mapNotNull { entry -> entry.problem?.let {
            TextRange(entry.range.start, entry.range.end).shiftRight(context.host.textRange.startOffset) to it
        } }
        return Template(context, HostSqlEntries(entries.map { HostSqlEntry(TextRange(it.range.start, it.range.end), it.replacement, it.placeholder) },
            context.shared.trimSteps.map { trim ->
                when (trim.function) {
                    "trimIndent" -> HostSqlTrim.Indent
                    "trimMargin" -> HostSqlTrim.Margin(requireNotNull(trim.marginPrefix))
                    else -> error("Unknown helper trim: ${trim.function}")
                }
            }),
            BrikkDuckdbProfile(context.shared.binds, context.shared.slots, diagnostics.map { it.second }.distinct()), diagnostics)
    }
}
