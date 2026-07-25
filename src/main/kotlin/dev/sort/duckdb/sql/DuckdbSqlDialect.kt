package dev.sort.duckdb.sql

import com.intellij.database.Dbms
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.functions.SqlFunctionsUtil
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.sql.dialects.postgres.PgDialectBase
import com.intellij.sql.dialects.postgres.PgTokens
import dev.sort.duckdb.DuckdbDbms

/**
 * The "DuckDB (Brikk)" SQL dialect, based on Postgres ([PgDialectBase]).
 *
 * DuckDB's grammar is Postgres-derived (its parser started from the PG parser; `::` casts,
 * `$$`-quoting, PG operators), so PG is the substrate that mis-parses the LEAST — the same
 * evidence-based substrate call the Doris plugin made with MySQL (Doris speaks MySQL wire and
 * MySQL-flavored SQL). DuckDB-only syntax the PG grammar can't parse (star `EXCLUDE`/`REPLACE`,
 * `GROUP BY ALL`, lambdas, `PIVOT`, FROM-first statements, ...) is measured by
 * DuckdbSyntaxProbeTest's scoreboard and will be handled with the doris-intellij playbook:
 * lenient statement boundaries in [DuckdbPsiParser], lexer masking only where structurally
 * unavoidable, and an authoritative validator (an in-process DuckDB via the data source's own
 * driver — the analog of Doris's embedded fe-sql-parser, but always version-matched).
 *
 * The explicit [TokensHelper] mirrors the Doris lesson: the default createTokensHelper(Class)
 * resolves `functions.xml` relative to THIS class's package (which has none) and silently yields
 * an EMPTY builtin-function map, breaking special-form builtins (CAST(x AS t), EXTRACT, ...).
 * Loading PgDialect's own definitions keeps us in sync with the platform.
 */
class DuckdbSqlDialect private constructor() : PgDialectBase("DuckDBSQL") {
    override fun getDbms(): Dbms = DuckdbDbms.DUCKDB_BRIKK

    // PgDialectBase leaves these abstract (the concrete per-version dialects fill them in).
    // Delegate to modern PG so operator/system-variable knowledge stays in sync with the platform
    // — the same reason the tokens helper below loads PgDialect's own function definitions.
    override fun isOperatorSupported(op: com.intellij.psi.tree.IElementType): Boolean =
        PgDialect.INSTANCE.isOperatorSupported(op)

    override fun getSystemVariables(): MutableSet<String> = PgDialect.INSTANCE.systemVariables

    override fun createTokensHelper(): TokensHelper =
        TokensHelper(
            PgTokens::class.java,
            SqlFunctionsUtil.loadFunctionDefinition(PgDialect.INSTANCE)
        )

    companion object {
        @JvmField
        val INSTANCE = DuckdbSqlDialect()
    }
}
