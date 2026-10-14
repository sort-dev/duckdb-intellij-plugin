package dev.sort.duckdb.catalog

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.database.model.ObjectKind
import dev.brikk.house.sql.intellij.catalog.OutOfScopeClassifier
import dev.brikk.house.sql.intellij.catalog.ReferenceScope
import dev.sort.duckdb.sql.DuckdbSqlDialect

/** The shared classifier supplies the namespace; the shared service owns queueing/retry/model sync. */
class DuckdbReferenceIntrospectAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        if (file.language != DuckdbSqlDialect || DumbService.isDumb(file.project)) return
        val requests = file.project.service<DuckdbAutoIntrospectService>()
        if (!requests.profile.autoIntrospect || !requests.profile.outOfScopeReferences) return
        val classifier = OutOfScopeClassifier(requests)
        for (reference in PsiTreeUtil.findChildrenOfType(file, SqlReferenceExpression::class.java)) {
            if (reference.referenceElementType.targetKind != ObjectKind.TABLE) continue
            val classified = classifier.classify(reference)
            if (classified is ReferenceScope.OutOfScope) {
                requests.discovery.request(file, classified.namespace.path)
                continue
            }
            val path = generateSequence(reference) { it.qualifierExpression as? SqlReferenceExpression }
                .mapNotNull { it.name }.toList().asReversed()
            if (path.size > 1) {
                // Native resolve may return an inline placeholder for an unloaded qualified name.
                // The shared service still checks that the exact namespace is listed and childless.
                requests.discovery.request(file, path.dropLast(1))
                continue
            }
            val current = requests.profile.dataSources.consoleNamespace(file)
            if (current.isNotEmpty()) requests.discovery.request(file, current)
        }
    }
}
