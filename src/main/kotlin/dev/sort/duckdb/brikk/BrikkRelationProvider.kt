package dev.sort.duckdb.brikk

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiFile
import dev.brikk.house.intellij.ShapeResult

/** Optional providers copy fresh function-local contracts; no global slot registry. */
interface BrikkRelationProvider {
    fun relations(file: PsiFile): Map<String, ShapeResult>
    companion object {
        val EP = ExtensionPointName.create<BrikkRelationProvider>("dev.sort.duckdb.brikkRelations")
        fun forFile(file: PsiFile): Map<String, ShapeResult> =
            if (BrikkDuckdbProfile.forFile(file) == null) emptyMap()
            else EP.extensionList.firstNotNullOfOrNull { provider -> provider.relations(file).takeIf { it.isNotEmpty() } }.orEmpty()
    }
}
