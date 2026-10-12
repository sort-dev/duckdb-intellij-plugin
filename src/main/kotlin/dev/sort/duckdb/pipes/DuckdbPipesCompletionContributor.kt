package dev.sort.duckdb.pipes

import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.pipe.PipeStageCompletionContributor
import dev.brikk.house.sql.intellij.pipe.PipeStageKeywordAnnotator
import dev.brikk.house.sql.shape.ShapeCatalog

class DuckdbPipesCompletionContributor : PipeStageCompletionContributor(DuckdbPipeDialect) {
    override fun inputCatalog(file: PsiFile, input: String) = ShapeCatalog.EMPTY
}

class DuckdbPipeStageKeywordAnnotator : PipeStageKeywordAnnotator(DuckdbPipeDialect)
