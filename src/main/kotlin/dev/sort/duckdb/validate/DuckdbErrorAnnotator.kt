package dev.sort.duckdb.validate

import com.intellij.lang.Language
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorProfile
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.isEditingEnabled
import dev.brikk.house.sql.intellij.pipe.lower
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.parser.SyntacticRange
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.shape.ShapeError
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import dev.sort.duckdb.brikk.DuckdbHostSql
import dev.brikk.house.sql.shape.SqlFragment
import dev.sort.duckdb.pipes.DuckdbPipeDialect
import dev.sort.duckdb.sql.DuckdbSqlDialect

/** Parse-only native errors, with shared stale-result checks and injected source mapping. */
class DuckdbErrorAnnotator : NativeErrorAnnotator(DuckdbNativeErrorProfile)

internal object DuckdbNativeErrorProfile : NativeErrorProfile {
    override val language get() = DuckdbSqlDialect
    // Kotlin strings, and blocks of SQL Transpiler's polyglot .bsql files.
    override val injectionHosts get() = setOfNotNull(Language.findLanguageByID("kotlin"), Language.findLanguageByID("BrikkSQL"))
    override val dialect = "duckdb"
    override val label = "DuckDB"
    override val sizeCap = 100_000
    override val hostSql get() = DuckdbHostSql

    private data class State(val pipes: Boolean, val brikk: BrikkDuckdbProfile?, val slots: Set<String>)
    private data class Revision(val state: State, val count: Long)
    private val stateKey = Key.create<Revision>("duckdb.native.errors.state")

    override fun validator(file: PsiFile): NativeSqlValidator? {
        capture(file)
        if (BrikkDuckdbProfile.forFile(file) != null) return null
        val jar = DuckdbEngineLocator.engineJarFor(file)?.toString()
        return service<DuckdbEngineDriverService>().drivers.validatorForJar(jar)
    }

    // Only this read-side hook touches PSI or relation providers. Background hooks use copied data.
    private fun capture(file: PsiFile): Revision {
        val brikk = BrikkDuckdbProfile.forFile(file)?.let {
            it.copy(binds = it.binds.toList(), slots = it.slots.toList(), issues = it.issues.toList())
        }
        val state = State(DuckdbPipeDialect.isEditingEnabled(file), brikk,
            brikk?.slots?.toSet().orEmpty())
        val previous = file.getUserData(stateKey)
        if (previous?.state == state) return previous
        return Revision(state, (previous?.count ?: 0) + 1).also { file.putUserData(stateKey, it) }
    }

    override fun modificationCount(file: PsiFile): Long = capture(file).count

    override fun statementDiagnostics(file: PsiFile, script: String, statement: SqlStatementRange): List<SqlDiagnostic>? {
        val state = file.getUserData(stateKey)?.state ?: return null
        if (state.brikk != null) {
            // Brikk fragments still require exactly one statement, checked once over the whole text.
            if (statement.start != 0 || state.brikk.issues.isNotEmpty()) return emptyList()
            val refusal = try {
                SqlFragment(script, "duckdb").ast
                null
            } catch (e: ParseError) {
                val first = e.errors.firstOrNull()
                PipeTranslation.Refused(first?.start?.let { SyntacticRange(it, first.end ?: it + 1) },
                    first?.description ?: "Invalid Brikk SQL")
            } catch (e: TokenError) {
                PipeTranslation.Refused(null, e.message ?: "Invalid SQL token")
            } catch (e: ShapeError) {
                PipeTranslation.Refused(null, e.message ?: "Brikk SQL requires exactly one statement")
            }
            return refusal?.let { listOf(diagnostic(script, 0, it, "DuckDB Brikk")) } ?: emptyList()
        }
        if (!state.pipes || !statement.hasPipe) return null
        val text = script.substring(statement.start, statement.end)
        val result = recoverEditorOperation { DuckdbPipeDialect.lower(text) }.getOrElse {
            PipeTranslation.Refused(null, "PIPE translation failed: ${it.message}")
        }
        val refusal = when (result) {
            is PipeTranslation.Refused -> result
            is PipeTranslation.Lowered -> result.blocker?.let { PipeTranslation.Refused(null, it) }
            else -> null
        }
        return refusal?.let { listOf(diagnostic(text, statement.start, it, DuckdbPipeDialect.label)) } ?: emptyList()
    }

    private fun diagnostic(text: String, offset: Int, error: PipeTranslation.Refused, label: String): SqlDiagnostic {
        val start = error.range?.start?.coerceIn(0, text.length)
            ?: text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val first = if (start == text.length) text.offsetByCodePoints(start, -1) else start
        val end = maxOf(error.range?.end ?: 0, text.offsetByCodePoints(first, 1)).coerceAtMost(text.length)
        return SqlDiagnostic(DiagnosticSeverity.ERROR, "$label: ${error.message}", offset + first, offset + end, DiagnosticSource.OTHER)
    }
}
