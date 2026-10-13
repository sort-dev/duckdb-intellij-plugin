package dev.sort.duckdb.brikk.kotlin

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Host diagnostics target authored Kotlin, never a synthetic SQL placeholder. */
class BrikkDuckdbHostAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val host = element as? KtStringTemplateExpression ?: return
        val context = BrikkDuckdbKotlinContext.find(host) ?: return
        val template = BrikkDuckdbKotlinContext.template(context)
        holder.newAnnotation(HighlightSeverity.INFORMATION,
            if (context.pending) "DuckDB Brikk context: waiting for indexing. Inspection SQL is not executable."
            else "DuckDB Brikk context: PIPE editing enabled; scalar Kotlin references are named binds. Inspection SQL is not executable.")
            .range(host).create()
        template.diagnostics.forEach { (range, message) ->
            holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create()
        }
    }
}
