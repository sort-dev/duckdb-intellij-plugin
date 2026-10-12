package dev.sort.duckdb.catalog

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.ExecutionObserver
import dev.brikk.house.sql.intellij.catalog.ExecutionObserverProfile
import dev.brikk.house.sql.intellij.catalog.ExecutionObserverStartup
import dev.brikk.house.sql.intellij.catalog.HarvestConnectionInterceptor
import dev.brikk.house.sql.intellij.catalog.HarvestTrigger
import dev.brikk.house.sql.intellij.catalog.LiveCatalogCleanup
import dev.brikk.house.sql.intellij.catalog.LiveCatalogProfile
import dev.brikk.house.sql.intellij.catalog.LiveCatalogService
import dev.brikk.house.sql.intellij.catalog.LiveMergePolicy
import dev.brikk.house.sql.intellij.catalog.RefreshLiveCatalogAction
import dev.brikk.house.sql.intellij.catalog.StatementHeadDetector
import dev.brikk.house.sql.intellij.catalog.StatementRules
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.sort.duckdb.DuckdbDbms
import kotlinx.coroutines.CoroutineScope

object DuckdbLiveCatalogProfile : LiveCatalogProfile {
    override val dbms get() = DuckdbDbms.DUCKDB_BRIKK
    override val productLabel = "DuckDB"
    override val metadata = DUCKDB_METADATA
    override val harvester = DuckdbCatalogHarvester
    override val trigger = HarvestTrigger.EVERY_CONSOLE_CONNECT
    override val mergePolicy = LiveMergePolicy.REPLACE
    override val persistenceId = "duckdb-brikk"
    override val notificationGroupId = "DuckDB (Brikk)"
    override val legacyFiles = DuckdbLegacyCatalogFiles()
    override val dataSources get() = duckdbDataSourceResolver
}

@Service(Service.Level.APP)
class DuckdbLiveCatalogService(scope: CoroutineScope) : LiveCatalogService(scope) {
    override val profile = DuckdbLiveCatalogProfile
}

class DuckdbHarvestConnectionInterceptor : HarvestConnectionInterceptor(DuckdbLiveCatalogService::class.java)
class DuckdbLiveCatalogCleanup : LiveCatalogCleanup(DuckdbLiveCatalogService::class.java)
class DuckdbRefreshLiveCatalogAction : RefreshLiveCatalogAction(DuckdbLiveCatalogService::class.java)

object DuckdbExecutionObserverProfile : ExecutionObserverProfile {
    override val dbms get() = DuckdbDbms.DUCKDB_BRIKK
    override val dataSources get() = duckdbDataSourceResolver
    override val liveCatalog = DuckdbLiveCatalogService::class.java
    override val statements = StatementHeadDetector(StatementRules.DUCKDB.map { rule ->
        if (rule.pattern in setOf("INSTALL", "FORCE INSTALL", "LOAD")) rule.notifying() else rule
    }, "duckdb")
}

@Service(Service.Level.PROJECT)
class DuckdbExecutionObserver(project: Project, scope: CoroutineScope) : ExecutionObserver(project, scope) {
    override val profile = DuckdbExecutionObserverProfile
}

class DuckdbExecutionObserverStartup : ExecutionObserverStartup(DuckdbExecutionObserver::class.java)
