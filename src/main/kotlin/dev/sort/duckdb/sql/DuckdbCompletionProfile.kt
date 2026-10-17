package dev.sort.duckdb.sql

import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.completion.SqlCompletionContributor
import dev.brikk.house.sql.intellij.completion.SqlCompletionProfile
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.completion.SqlLookupStyles
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.sort.duckdb.catalog.DuckdbCatalogResolver

object DuckdbCompletionProfile : SqlCompletionProfile {
    override val language get() = DuckdbSqlDialect
    override val metadata = DUCKDB_METADATA

    override fun snapshot(file: PsiFile) = DuckdbCatalogResolver.activeCatalogFor(file).snapshot
    override fun functions(file: PsiFile) = DuckdbCatalogResolver.activeCatalogFor(file).functions
    override fun keywordCatalog(file: PsiFile, snapshot: MetadataSnapshot) =
        DuckdbCatalogResolver.activeCatalogFor(file).keywordCatalog

    override val nativeKinds = mapOf("macro" to SqlLookupKind.MACRO, "table_macro" to SqlLookupKind.TABLE_MACRO)
    override val styles = SqlLookupStyles.DEFAULT.let { defaults ->
        defaults.with(
            *listOf(
                SqlLookupKind.FUNCTION to "scalar",
                SqlLookupKind.AGGREGATE to "aggregate",
                SqlLookupKind.MACRO to "macro",
                SqlLookupKind.TABLE_FUNCTION to "table",
                SqlLookupKind.TABLE_MACRO to "table_macro",
            ).map { (kind, label) ->
                kind to defaults[kind].copy(typeText = label, showsSignature = false, showsReturnType = false)
            }.toTypedArray(),
        )
    }
}

class DuckdbMetadataCompletionContributor : SqlCompletionContributor(DuckdbCompletionProfile)
