package dev.sort.duckdb.validate

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStatement
import dev.sort.duckdb.sql.DuckdbSqlDialect

/**
 * Editor surface for [DuckdbEngineValidator]: statement text goes to a real in-memory DuckDB,
 * engine-confirmed PARSER errors come back as squiggles carrying the engine's own message —
 * version-exact, zero grammar drift (the doris fe-sql-parser annotator role). Files stay silent
 * when no engine is reachable; binder/catalog opinions are never surfaced (the das layer owns
 * resolution).
 */
class DuckdbErrorAnnotator : ExternalAnnotator<DuckdbErrorAnnotator.Info, DuckdbErrorAnnotator.Result>() {

    data class Info(val statements: List<Pair<TextRange, String>>)
    data class Result(val errors: List<Pair<TextRange, DuckdbEngineValidator.EngineError>>)

    // The default 3-arg variant bails when the file has PSI errors — but broken-looking PSI is
    // exactly when the engine's opinion matters most (the substrate can't parse everything).
    override fun collectInformation(file: PsiFile, editor: com.intellij.openapi.editor.Editor, hasErrors: Boolean): Info? =
        collectInformation(file)

    override fun collectInformation(file: PsiFile): Info? {
        if (!file.language.isKindOf(DuckdbSqlDialect.INSTANCE)) return null
        if (!DuckdbEngineValidator.available) return null
        val statements = PsiTreeUtil.findChildrenOfType(file, SqlStatement::class.java)
            .filterNot { it.parent is SqlStatement }
            .map { it.textRange to it.text }
        return if (statements.isEmpty()) null else Info(statements)
    }

    override fun doAnnotate(collectedInfo: Info?): Result? {
        val info = collectedInfo ?: return null
        val flagged = DuckdbEngineValidator.validate(info.statements.map { it.second })
        if (flagged.isEmpty()) return Result(emptyList())
        return Result(flagged.map { (i, err) -> info.statements[i].first to err })
    }

    override fun apply(file: PsiFile, annotationResult: Result?, holder: AnnotationHolder) {
        val result = annotationResult ?: return
        val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for ((stmtRange, err) in result.errors) {
            val range = errorRange(doc, stmtRange, err)
            holder.newAnnotation(HighlightSeverity.ERROR, "DuckDB: ${err.message}")
                .range(range)
                .create()
        }
    }

    /** Map the engine's statement-relative line + near-token onto a document range. */
    private fun errorRange(doc: Document, stmt: TextRange, err: DuckdbEngineValidator.EngineError): TextRange {
        val stmtStartLine = doc.getLineNumber(stmt.startOffset)
        val targetLine = (stmtStartLine + err.line - 1).coerceAtMost(doc.lineCount - 1)
        val lineStart = doc.getLineStartOffset(targetLine).coerceAtLeast(stmt.startOffset)
        val lineEnd = doc.getLineEndOffset(targetLine).coerceAtMost(stmt.endOffset)
        if (lineEnd <= lineStart) return stmt
        val near = err.nearToken
        if (!near.isNullOrBlank()) {
            val lineText = doc.charsSequence.subSequence(lineStart, lineEnd).toString()
            val at = lineText.indexOf(near)
            if (at >= 0) return TextRange(lineStart + at, lineStart + at + near.length)
        }
        return TextRange(lineStart, lineEnd)
    }
}
