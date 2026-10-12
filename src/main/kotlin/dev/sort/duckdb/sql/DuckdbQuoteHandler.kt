package dev.sort.duckdb.sql

import dev.brikk.house.sql.intellij.editor.SqlDialectQuoteHandler

class DuckdbQuoteHandler : SqlDialectQuoteHandler(EnterInString.Split)
