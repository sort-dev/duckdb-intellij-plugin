package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.DbOptionProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project

/** The native dialog must never save grammar preferences, including during draft checks or Test Connection. */
class DuckdbGrammarOptionProvider : DbOptionProvider.LocalDataSourceOptionProvider<Boolean>() {
    override fun isApplicable(project: Project?, target: LocalDataSource) = project != null && DuckdbGrammarSources.accepts(target)
    override fun getDisplayName() = "Grammars"
    override fun getDescription() = "DuckDB grammar activation for this data source in the current project"
    override fun getLocation() = DbOptionProvider.UiLocation.NEW_TAB
    // Added to DbOptionProvider in 263, absent from the 262 compile SDK. Keep the erased
    // Object -> Object ABI explicitly: a LocalDataSource/Boolean overload would lack its bridge.
    // This is only the native dialog's placeholder, never the project-owned grammar profile.
    fun getStoredDefault(target: Any?): Any = false
    override fun load(target: LocalDataSource?) = false
    override fun store(target: LocalDataSource, value: Boolean?) = Unit
    override fun getEditor(target: LocalDataSource, dataContext: DataContext): DbOptionProvider.EditorDesc<Boolean> {
        val project = requireNotNull(CommonDataKeys.PROJECT.getData(dataContext))
        val panel = DuckdbGrammarEditor(project, target)
        return DbOptionProvider.EditorDesc(panel, { }, { false })
    }
}
