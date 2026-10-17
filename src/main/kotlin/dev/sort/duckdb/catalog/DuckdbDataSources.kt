package dev.sort.duckdb.catalog

import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.sort.duckdb.DuckdbDbms.DUCKDB_BRIKK

internal val duckdbDataSourceResolver = DialectDataSourceResolver(DUCKDB_BRIKK)
