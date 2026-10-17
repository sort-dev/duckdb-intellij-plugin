package dev.sort.duckdb.brikk.kotlin

import com.intellij.ide.BrowserUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotifications
import dev.brikk.house.intellij.BrikkAction
import dev.brikk.house.intellij.BrikkActionKind
import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkHosts
import dev.brikk.house.sql.intellij.host.HostSqlActionBanner

internal fun relevantBrikkAction(action: BrikkAction): Boolean =
    action.kind != BrikkActionKind.DIALECT_PLUGIN_MISSING || action.dialect == BrikkDialect.DUCKDB

class BrikkDuckdbActionBanner : HostSqlActionBanner() {
    override fun actionNeeded(project: Project, file: VirtualFile): Action? =
        PsiManager.getInstance(project).findFile(file)?.let(::actionFor)

    internal fun actionFor(file: PsiFile): Action? {
        if (file.language.id != "kotlin") return null
        val action = BrikkHosts.actionNeeded(file)?.takeIf(::relevantBrikkAction)
            ?: BrikkHosts.actionsNeeded(file).firstOrNull(::relevantBrikkAction) ?: return null
        if (action.buildFileUrl == null && action.docsUrl == null) return null
        return Action(action.message, action.fixText, Runnable {
            val build = action.buildFileUrl?.let { VirtualFileManager.getInstance().findFileByUrl(it) }
            if (build != null) OpenFileDescriptor(file.project, build).navigate(true)
            else action.docsUrl?.let(BrowserUtil::browse)
        })
    }
}

private val lastActions = Key.create<List<BrikkAction>>("duckdb.brikk.banner.actions")

/** Action reads are cheap and analysis-free. Refresh the editor only when recorded actions change. */
internal fun updateBrikkActionBanner(file: PsiFile) {
    val host = InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file)
    val actions = BrikkHosts.actionsNeeded(host).filter(::relevantBrikkAction)
    val previous = host.getUserData(lastActions).orEmpty()
    if (actions == previous) return
    host.putUserData(lastActions, actions)
    EditorNotifications.getInstance(file.project).updateNotifications(host.virtualFile)
}
