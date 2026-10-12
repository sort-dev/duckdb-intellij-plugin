package dev.sort.duckdb.sql

import dev.brikk.house.sql.metadata.DUCKDB_METADATA

internal val DUCKDB_KEYWORDS: Set<String> =
    DUCKDB_METADATA.default.keywords?.keywords.orEmpty().mapTo(HashSet()) { it.word.uppercase() }
