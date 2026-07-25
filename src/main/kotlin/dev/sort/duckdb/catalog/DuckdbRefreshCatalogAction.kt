package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * "Refresh DuckDB Catalog" — the manual half of the live-catalog refresh (plugin.xml `<actions>`;
 * editor context menu on DuckDB (Brikk) files + Find Action anywhere).
 *
 * Target resolution mirrors [DuckdbCatalogResolver] exactly: the file's console data source when
 * there is one, else the project's single DuckDB (Brikk) data source; disabled when neither
 * resolves. The refresh runs on a pooled thread ([DuckdbCatalogRefresh.harvestNow] blocks on the
 * helper connect) and finishes with a balloon either way — the success balloon lists the loaded
 * extensions, which is how users confirm AUTOLOADED extensions arrived (autoload never executes
 * an INSTALL/LOAD the observer could see, so this action is that path's only trigger).
 */
// API status: AnAction.update/actionPerformed are @ApiStatus.OverrideOnly (we override — the
// annotation's intent); getActionUpdateThread, ActionUpdateThread, ActionPlaces, CommonDataKeys,
// Application.executeOnPooledThread all javap-verified flag-free on DataGrip 2026.1.3.
class DuckdbRefreshCatalogAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val file = e.getData(CommonDataKeys.PSI_FILE)
        // Editor popup: only surface on our dialect's files (the spec'd surface). Everywhere else
        // (Find Action and friends) stay visible and let enablement carry the signal.
        val visible = e.place != ActionPlaces.EDITOR_POPUP || isOurFile(file)
        val target = if (visible) resolveTarget(project, file) else null
        e.presentation.isVisible = visible
        e.presentation.isEnabled = target != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = resolveTarget(project, e.getData(CommonDataKeys.PSI_FILE))
        if (target == null) {
            DuckdbCatalogRefresh.notify(project, DuckdbCatalogRefresh.Outcome.NoDataSource)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = DuckdbCatalogRefresh.harvestNow(project, target)
            DuckdbCatalogRefresh.notify(project, outcome)
        }
    }

    private fun isOurFile(file: PsiFile?): Boolean =
        file != null && runCatching { file.language.id == "DuckDBSQL" }.getOrDefault(false)

    private fun resolveTarget(project: Project, file: PsiFile?): LocalDataSource? =
        if (file != null) DuckdbCatalogResolver.dataSourceFor(file)
        else DuckdbCatalogResolver.singleDataSource(project)
}
