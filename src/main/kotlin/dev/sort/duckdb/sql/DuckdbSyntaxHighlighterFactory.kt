package dev.sort.duckdb.sql

import dev.brikk.house.sql.intellij.editor.KeywordOverlayHighlighterFactory

class DuckdbSyntaxHighlighterFactory : KeywordOverlayHighlighterFactory(DuckdbSqlDialect, DUCKDB_KEYWORDS)
