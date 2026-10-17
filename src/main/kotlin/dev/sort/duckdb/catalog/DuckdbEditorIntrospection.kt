package dev.sort.duckdb.catalog

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import dev.brikk.house.sql.intellij.catalog.IntrospectBannerProvider
import dev.brikk.house.sql.intellij.catalog.QualifiedPathIntrospectContributor

/** Also protect other sessions of this data source, explicit BEGIN and requests still executing. */
class DuckdbQualifiedPathIntrospectContributor : QualifiedPathIntrospectContributor(DuckdbAutoIntrospectService::class.java) {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.project.getService(DuckdbIntrospectionTransactions::class.java).blocked(parameters.originalFile)) return
        super.fillCompletionVariants(parameters, result)
    }
}

class DuckdbIntrospectBannerProvider : IntrospectBannerProvider(DuckdbAutoIntrospectService::class.java)
