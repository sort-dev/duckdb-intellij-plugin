package dev.sort.duckdb.brikk

import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.brikk.house.intellij.ShapeKnowledge

/** Own uncertainty-aware contract diagnostics; never claim a partial row lacks a runtime column. */
class BrikkSqlContractAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        val analysis = BrikkSqlEditorSupport.analysis(file) ?: return
        val manager = InjectedLanguageManager.getInstance(file.project)
        for (reference in analysis.references) {
            if (reference.kind != BrikkSqlScopes.Kind.COLUMN || reference.name == "*") continue
            val relations = if (reference.qualifier.isNotBlank()) listOfNotNull(reference.scope.relation(reference.qualifier))
                else reference.scope.relations
            if (relations.isEmpty() || relations.any { !it.managed || !it.available }) continue
            val matches = reference.scope.candidates(reference.name, reference.qualifier)
            val message = when {
                matches.size > 1 -> "Ambiguous relation column '${reference.name}'; qualify it with a slot alias."
                matches.isNotEmpty() -> null
                relations.any { it.knowledge == ShapeKnowledge.PARTIAL } -> "Column '${reference.name}' is not guaranteed by this partial relation contract."
                else -> "Column '${reference.name}' is not present in this full relation contract."
            } ?: continue
            val start = manager.mapUnescapedOffsetToInjected(file, reference.start)
            val end = manager.mapUnescapedOffsetToInjected(file, reference.end)
            val range = TextRange(start, end.coerceAtMost(file.textLength))
            if (manager.intersectWithAllEditableFragments(file, range).isEmpty()) continue
            val severity = if (matches.isEmpty() && relations.any { it.knowledge == ShapeKnowledge.PARTIAL }) HighlightSeverity.WARNING else HighlightSeverity.ERROR
            holder.newAnnotation(severity, message).range(range).create()
        }
    }
}
