package dev.sort.duckdb.brikk

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile

/** Editing metadata only. Contains no Kotlin/plugin types and no runtime parameter values. */
data class BrikkDuckdbProfile(val binds: List<String>, val slots: List<String>, val issues: List<String>) {
    companion object {
        val KEY: Key<BrikkDuckdbProfile> = Key.create("dev.sort.duckdb.brikk.editingProfile")
        fun forFile(file: PsiFile?): BrikkDuckdbProfile? = file?.getUserData(KEY)
    }
}

internal fun isBrikkInjectedSql(file: PsiFile): Boolean = BrikkDuckdbProfile.forFile(file) != null &&
    InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
