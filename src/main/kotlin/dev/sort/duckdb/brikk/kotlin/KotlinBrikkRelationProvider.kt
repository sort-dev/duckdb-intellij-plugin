package dev.sort.duckdb.brikk.kotlin

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import dev.brikk.house.intellij.*
import dev.sort.duckdb.brikk.BrikkRelationProvider

class KotlinBrikkRelationProvider : BrikkRelationProvider {
    override fun relations(file: PsiFile): Map<String, ShapeResult> {
        val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: return emptyMap()
        return when (val result = BrikkKotlinContext.inspect(host, includeShapes = true)) {
            is ContextResult.Available -> if (result.context.dialect == BrikkDialect.DUCKDB) result.context.relations else emptyMap()
            // No negative caching: a later request retries cold/plugin/schema resolution.
            else -> emptyMap()
        }
    }
}
