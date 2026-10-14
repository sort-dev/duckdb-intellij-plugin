package dev.sort.duckdb.catalog

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectService
import dev.brikk.house.sql.intellij.catalog.OutOfScopeReferenceFilter
import dev.brikk.house.sql.intellij.catalog.IntrospectNamespaceIntention
import kotlinx.coroutines.CoroutineScope

@Service(Service.Level.PROJECT)
class DuckdbAutoIntrospectService(project: Project, scope: CoroutineScope) : AutoIntrospectService(project, scope) {
    override val profile = DuckdbCatalogProfile
    internal val discovery = DuckdbNamespaceDiscovery(project, scope, this)
}

class DuckdbOutOfScopeReferenceFilter : OutOfScopeReferenceFilter(DuckdbAutoIntrospectService::class.java)
class DuckdbIntrospectNamespaceIntention : IntrospectNamespaceIntention(DuckdbAutoIntrospectService::class.java, "DuckDB")
