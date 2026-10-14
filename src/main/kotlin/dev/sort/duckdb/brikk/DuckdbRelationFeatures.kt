package dev.sort.duckdb.brikk

import com.intellij.openapi.extensions.ExtensionPointName
import dev.brikk.house.sql.intellij.catalog.DasRelationFacts
import dev.brikk.house.sql.intellij.relation.*
import dev.sort.duckdb.catalog.duckdbDataSourceResolver
import dev.sort.duckdb.catalog.DuckdbCatalogResolver
import dev.sort.duckdb.sql.DuckdbSqlDialect

/** Kotlin facts are loaded only through the optional descriptor; physical facts work without Kotlin. */
object DuckdbRelationProfile : RelationScopeProfile {
    val FACTS = ExtensionPointName.create<ExternalRelationFacts>("dev.sort.duckdb.relationFacts")
    private val physical = DasRelationFacts(duckdbDataSourceResolver)
    private val tableFunctions = TableFunctionFacts { DuckdbCatalogResolver.activeCatalogFor(it).snapshot }
    override val language get() = DuckdbSqlDialect
    override val dialect = "duckdb"
    override val providers get() = FACTS.extensionList + physical + tableFunctions
    override val hostSql get() = DuckdbHostSql
}

class DuckdbRelationResolveExtension : ExternalRelationResolveExtension(DuckdbRelationProfile)
class DuckdbRelationInspectionSuppressor : ExternalRelationInspectionSuppressor(DuckdbRelationProfile)
class DuckdbRelationCompletionContributor : ExternalRelationCompletionContributor(DuckdbRelationProfile)
class DuckdbRelationGotoDeclarationHandler : ExternalRelationGotoDeclarationHandler(DuckdbRelationProfile)
class DuckdbRelationAnnotator : ExternalRelationAnnotator(DuckdbRelationProfile)
