package dev.sort.duckdb.pipes

import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

class DuckdbPipesConfigurable(private val project: Project) : SearchableConfigurable {
    private var checkbox: JBCheckBox? = null
    override fun getId() = "dev.sort.duckdb.pipes"
    override fun getDisplayName() = "DuckDB PIPE"
    override fun createComponent(): JComponent {
        val vetoed = "false".equals(System.getProperty(DuckdbPipes.PROPERTY), true)
        return JPanel(BorderLayout()).apply {
            add(JBCheckBox("Enable PIPE syntax in this project").also {
                checkbox = it
                it.isEnabled = !vetoed
            }, BorderLayout.NORTH)
            add(JBLabel(if (vetoed) "Disabled by -Dduckdb.pipes=false."
                else "Transpile PIPE queries to DuckDB SQL on execution. SQL Transpiler is not required."))
            reset()
        }
    }
    override fun isModified() = checkbox?.isSelected?.let { it != project.service<DuckdbPipesSettings>().enabled } ?: false
    override fun apply() { checkbox?.let { project.service<DuckdbPipesSettings>().enabled = it.isSelected } }
    override fun reset() { checkbox?.isSelected = project.service<DuckdbPipesSettings>().enabled }
    override fun disposeUIResources() { checkbox = null }
}
