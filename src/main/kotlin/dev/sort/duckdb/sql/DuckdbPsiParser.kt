package dev.sort.duckdb.sql

import com.intellij.sql.dialects.postgres.PgParser

/**
 * DuckDB statement parsing on the PG foundation.
 *
 * Seed state: a pure pass-through. The doris-intellij playbook applies as the syntax scoreboard
 * (DuckdbSyntaxProbeTest) demands it: override parseSqlStatement, detect DuckDB-only statements
 * with bounded keyword look-ahead, and lenient-parse them (consume to `;`, done(SQL_STATEMENT))
 * so statement boundaries and run-boxes stay correct even where the PG grammar chokes —
 * FROM-first statements, PIVOT/UNPIVOT, ATTACH/DETACH, COPY variants, SUMMARIZE, ...
 */
// PgParser's ctor takes only the legacy-version flag (false = modern PG, what PgParserDefinition
// itself passes) — unlike MysqlParser it does not accept the dialect. Seed pragmatism: inherit
// with the modern flag; if fixture evidence ever shows the parser's internal dialect identity
// mattering (element types are shared Sql* types, so it shouldn't), the seam is PgParserBase's
// (dialect, flag) constructor.
class DuckdbPsiParser : PgParser(false)
