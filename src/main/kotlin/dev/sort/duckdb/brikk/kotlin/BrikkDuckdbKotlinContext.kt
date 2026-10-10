package dev.sort.duckdb.brikk.kotlin

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import dev.brikk.house.intellij.*
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Dialect projection only. All Brikk symbol/entry semantics live in the shared helper. */
internal object BrikkDuckdbKotlinContext {
    private val lastContext = Key.create<BrikkContext>("dev.sort.duckdb.brikk.lastRecognizedContext")
    data class Context(val host: KtStringTemplateExpression, val shared: BrikkContext) {
        val trims get() = shared.trims
    }
    data class Piece(val range: TextRange, val replacement: String? = null)
    data class Template(val context: Context, val pieces: List<Piece>, val profile: BrikkDuckdbProfile,
                        val diagnostics: List<Pair<TextRange, String>>)

    fun find(host: KtStringTemplateExpression): Context? = when (val result = BrikkKotlinContext.inspect(host, includeShapes = false)) {
        is ContextResult.Available -> {
            if (result.context.dialect != BrikkDialect.DUCKDB) {
                host.putUserData(lastContext, null)
                null
            } else {
                host.putUserData(lastContext, result.context)
                Context(host, result.context)
            }
        }
        ContextResult.NotBrikk -> { host.putUserData(lastContext, null); null }
        is ContextResult.Unavailable -> {
            // Only a previously resolved DuckDB host may retain an explicitly uncertain profile.
            // Never reuse old template ranges or guess a bind/Rel role during indexing.
            host.getUserData(lastContext)?.let { previous ->
                val pending = host.entries.mapIndexed { index, entry ->
                    val range = HostRange(entry.startOffsetInParent, entry.startOffsetInParent + entry.textLength)
                    if (entry.expression == null) TemplateEntry(range, EntryRole.LITERAL)
                    else TemplateEntry(range, EntryRole.PENDING, replacement = ":__brikk_pending_$index", problem = result.reason)
                }
                Context(host, previous.copy(entries = pending, relations = emptyMap()))
            }
        }
    }

    fun template(context: Context): Template {
        val entries = context.shared.entries
        val diagnostics = entries.mapNotNull { entry -> entry.problem?.let {
            TextRange(entry.range.start, entry.range.end).shiftRight(context.host.textRange.startOffset) to it
        } }
        return Template(context, entries.map { Piece(TextRange(it.range.start, it.range.end), it.replacement) },
            BrikkDuckdbProfile(context.shared.binds, context.shared.slots, diagnostics.map { it.second }.distinct()), diagnostics)
    }
}
