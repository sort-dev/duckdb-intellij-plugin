package dev.sort.duckdb.pipes

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JCheckBox

class DuckdbPipesConfigurableTest : BasePlatformTestCase() {
    fun testCheckboxKeepsTheProjectSetting() {
        val settings = project.service<DuckdbPipesSettings>()
        val previous = settings.enabled
        val configurable = DuckdbPipesConfigurable(project)
        try {
            settings.enabled = false
            val checkbox = configurable.createComponent().components.filterIsInstance<JCheckBox>().single()
            assertEquals("DuckDB PIPE support", checkbox.text)
            assertFalse(checkbox.isSelected)
            checkbox.isSelected = true
            assertTrue(configurable.isModified)
            configurable.apply()
            assertTrue(settings.enabled)
            configurable.reset()
            assertTrue(checkbox.isSelected)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            settings.enabled = previous
        }
    }
}
