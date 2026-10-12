package dev.sort.duckdb.sql

import dev.brikk.house.sql.intellij.editor.MaskedSpanRecolorAnnotator

class DuckdbMaskedSpanRecolorAnnotator : MaskedSpanRecolorAnnotator(DuckdbSqlDialect, "duckdb", DUCKDB_KEYWORDS)
