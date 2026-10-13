package dev.sort.duckdb.pipes

import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.pipe.PipeStageCompletionContributor

class DuckdbPipesCompletionContributor : PipeStageCompletionContributor(DuckdbPipeDialect) {
    // The shared relation contributor owns all columns, including offline computed outputs.
    override fun inputCatalog(file: PsiFile, input: String) = null
}
