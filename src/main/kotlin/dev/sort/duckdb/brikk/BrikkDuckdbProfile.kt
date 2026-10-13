package dev.sort.duckdb.brikk

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile

/** Editing metadata only. Contains no Kotlin/plugin types and no runtime parameter values. */
data class BrikkDuckdbProfile(val binds: List<String>, val slots: List<String>, val issues: List<String>) {
    companion object {
        val KEY: Key<BrikkDuckdbProfile> = Key.create("dev.sort.duckdb.brikk.editingProfile")
        fun forFile(file: PsiFile?): BrikkDuckdbProfile? {
            file ?: return null
            file.getUserData(KEY)?.let { return it }
            val host = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file.originalFile) ?: return null
            return ((DuckdbHostSql.state(host)?.claim as? dev.brikk.house.sql.intellij.host.HostSqlClaim.Injected)?.data as? DuckdbHostData)?.profile
        }
    }
}

/** Caller data for the shared channel's tooltip, with no Kotlin IDE types. */
data class DuckdbHostData(val profile: BrikkDuckdbProfile, val project: com.intellij.openapi.project.Project)
