package dev.sort.duckdb.brikk

import com.intellij.psi.PsiElement
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate

/** Only slot-owned references are intercepted. Physical table/column inspections remain native. */
class BrikkSqlInspectionSuppressor : SqlInspectionSuppressorDelegate {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId.removeSuffix("Inspection") !in setOf("SqlResolve", "SqlType", "SqlSignature")) return false
        val scoped = BrikkSqlEditorSupport.reference(element) ?: return false
        return when (scoped.kind) {
            BrikkSqlScopes.Kind.SLOT -> true
            BrikkSqlScopes.Kind.QUALIFIER -> scoped.scope.relation(scoped.name)?.managed == true
            BrikkSqlScopes.Kind.COLUMN -> if (scoped.qualifier.isNotBlank()) scoped.scope.relation(scoped.qualifier)?.managed == true
                else scoped.scope.relations.isNotEmpty() && scoped.scope.relations.all { it.managed }
        }
    }
}
