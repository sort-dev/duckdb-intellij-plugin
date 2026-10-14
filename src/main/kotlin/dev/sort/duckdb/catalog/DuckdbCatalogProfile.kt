package dev.sort.duckdb.catalog

import com.intellij.database.model.ObjectKind
import dev.brikk.house.sql.intellij.catalog.CatalogProfile
import dev.sort.duckdb.DuckdbDbms

object DuckdbCatalogProfile : CatalogProfile {
    override val dbms = DuckdbDbms.DUCKDB_BRIKK
    override val productLabel = "DuckDB"
    override val quoteChars = "\""
    override val levels = listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA)
    override val usesCurrentNamespace = true
    override val autoIntrospect = true
    override val outOfScopeReferences = true
    override val dataSources = duckdbDataSourceResolver
}
