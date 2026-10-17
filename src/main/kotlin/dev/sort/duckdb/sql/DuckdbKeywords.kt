package dev.sort.duckdb.sql

import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.KeywordCategory

internal val DUCKDB_KEYWORDS: Set<String> =
    DUCKDB_METADATA.default.keywords?.keywords.orEmpty().mapTo(HashSet()) { it.word.uppercase() }

internal val DUCKDB_OVERLAY_KEYWORDS: Set<String> =
    DUCKDB_METADATA.default.keywords?.keywords.orEmpty()
        .filter { it.category == KeywordCategory.RESERVED || it.category == KeywordCategory.TYPE_FUNCTION }
        .mapTo(HashSet()) { it.word.uppercase() }
