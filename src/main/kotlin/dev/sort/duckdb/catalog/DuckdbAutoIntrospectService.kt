package dev.sort.duckdb.catalog

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectService
import dev.brikk.house.sql.intellij.catalog.IntrospectBannerProvider
import dev.brikk.house.sql.intellij.catalog.QualifiedPathIntrospectContributor
import kotlinx.coroutines.CoroutineScope

@Service(Service.Level.PROJECT)
class DuckdbAutoIntrospectService(project: Project, scope: CoroutineScope) : AutoIntrospectService(project, scope) {
    override val profile = DuckdbCatalogProfile
}

class DuckdbIntrospectBannerProvider : IntrospectBannerProvider(DuckdbAutoIntrospectService::class.java)

class DuckdbQualifiedPathIntrospectContributor : QualifiedPathIntrospectContributor(DuckdbAutoIntrospectService::class.java)
