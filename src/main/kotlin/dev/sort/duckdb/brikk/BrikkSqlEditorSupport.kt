package dev.sort.duckdb.brikk

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import dev.brikk.house.intellij.NavigationTarget
import dev.brikk.house.intellij.ShapeResult

internal object BrikkSqlEditorSupport {
    fun contracts(file: PsiFile): Map<String, ShapeResult> {
        val profile = BrikkDuckdbProfile.forFile(file) ?: return emptyMap()
        val result = BrikkRelationProvider.forFile(file).toMutableMap()
        profile.slots.forEach { result.putIfAbsent(it, ShapeResult.Unavailable("Relation analysis is pending")) }
        return result
    }
    fun analysis(file: PsiFile): BrikkSqlScopes.Analysis? {
        if (BrikkDuckdbProfile.forFile(file) == null) return null
        val manager = InjectedLanguageManager.getInstance(file.project)
        return BrikkSqlScopes.analyze(manager.getUnescapedText(file), contracts(file))
    }
    fun reference(element: PsiElement, offset: Int = element.textRange.startOffset): BrikkSqlScopes.Reference? {
        val file = element.containingFile ?: return null
        val manager = InjectedLanguageManager.getInstance(file.project)
        return analysis(file)?.at(manager.mapInjectedOffsetToUnescaped(file, offset))
    }
    fun navigation(file: PsiFile, target: NavigationTarget?): PsiElement? {
        val location = target?.location ?: return null
        val vf = VirtualFileManager.getInstance().findFileByUrl(location.fileUrl) ?: return null
        val psi = PsiManager.getInstance(file.project).findFile(vf) ?: return null
        val leaf = psi.findElementAt(location.start) ?: return null
        return generateSequence(leaf) { it.parent }.firstOrNull { it is PsiNamedElement } ?: leaf
    }
}
