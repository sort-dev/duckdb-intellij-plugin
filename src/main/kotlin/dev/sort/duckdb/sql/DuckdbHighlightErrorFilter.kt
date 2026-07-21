package dev.sort.duckdb.sql

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement

/**
 * Stage-1 livability baseline (doris Phase-1 pattern): the PG substrate paints red
 * PsiErrorElements over every DuckDB-only expression it can't parse (`EXCLUDE`, struct literals,
 * lambdas, ...). Suppress base-parser syntax errors for DuckDB (Brikk) files entirely — DISPLAY
 * ONLY: the syntax scoreboard test keeps counting the real PsiErrorElements, so coverage truth is
 * never hidden from us, only from the user's editor.
 *
 * The authority that replaces this silence with REAL errors is Stage 3's PREPARE-validator
 * annotator (the engine itself); until then, no red is better than wrong red.
 */
class DuckdbHighlightErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val file = element.containingFile ?: return true
        return !file.language.isKindOf(DuckdbSqlDialect.INSTANCE)
    }
}
