package dev.sort.duckdb.sql

import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory

/**
 * The platform SQL highlighter for the DuckDB (Brikk) dialect. Beyond editor coloring, this
 * registration is LOAD-BEARING: the SQL todo/id indexers build their filter lexer from the
 * language's syntax highlighter — without it, ANY file mapped to the dialect crashes the TODO
 * index (PluginException: null from TodoIndex.computeValue; found by bisect, 2026-07-21).
 * DuckDB-only keyword coloring can layer on later (the doris DorisKeywordHighlighter pattern).
 */
class DuckdbSyntaxHighlighterFactory : SqlSyntaxHighlighterFactory.Base(DuckdbSqlDialect.INSTANCE)
