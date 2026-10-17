package dev.sort.duckdb.catalog

import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.catalog.CatalogSource
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.KeywordCatalog
import dev.brikk.house.sql.metadata.MetadataSnapshot

object DuckdbCatalogResolver {
    data class ActiveCatalog(
        val functions: List<FunctionPresentation>,
        val keywords: Set<String>,
        val live: Boolean,
        val dataSourceId: String?,
        val snapshot: MetadataSnapshot,
        val keywordCatalog: KeywordCatalog?,
    )

    fun activeCatalogFor(file: PsiFile): ActiveCatalog {
        val completion = ApplicationManager.getApplication().service<DuckdbLiveCatalogService>().completion(file)
        return ActiveCatalog(
            completion.functions,
            completion.keywords?.keywords.orEmpty().mapTo(LinkedHashSet()) { it.word.uppercase() },
            completion.selection.source != CatalogSource.Bundled,
            duckdbDataSourceResolver.resolve(file).dataSource?.uniqueId,
            completion.snapshot,
            completion.keywords,
        )
    }
}
