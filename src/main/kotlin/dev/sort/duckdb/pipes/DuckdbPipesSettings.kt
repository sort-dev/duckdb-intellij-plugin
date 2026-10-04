package dev.sort.duckdb.pipes

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.util.xmlb.annotations.OptionTag
import dev.sort.duckdb.sql.DuckdbSqlDialect

@Service(Service.Level.PROJECT)
@State(name = "DuckdbPipesSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DuckdbPipesSettings(private val project: Project) :
    SerializablePersistentStateComponent<DuckdbPipesSettings.Options>(Options()) {
    data class Options(@field:OptionTag("enabled") @JvmField val enabled: Boolean = false)
    @Volatile private var initialized = false
    var enabled: Boolean
        get() = state.enabled
        set(value) {
            if (value == state.enabled) return
            updateState { it.copy(enabled = value) }
            refreshFiles()
        }
    override fun initializeComponent() { initialized = true }
    override fun loadState(state: Options) {
        val changed = this.state.enabled != state.enabled
        super.loadState(state)
        if (initialized && changed) refreshFiles()
    }
    private fun refreshFiles() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val documents = PsiDocumentManager.getInstance(project)
            documents.commitAllDocuments()
            val psi = PsiManagerEx.getInstanceEx(project)
            val files = (psi.fileManager.allCachedFiles.map { it.virtualFile } +
                FileEditorManager.getInstance(project).openFiles).filter {
                it.isValid && psi.findFile(it)?.language?.isKindOf(DuckdbSqlDialect.INSTANCE) == true
            }.distinct()
            documents.reparseFiles(files, false)
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}
