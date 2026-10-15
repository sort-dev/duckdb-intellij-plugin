package dev.sort.duckdb

import com.intellij.database.actions.DatabaseViewActions
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DuckdbDriverPickerIconTest : BasePlatformTestCase() {
    fun testEmbeddedDriverInNewAndRecentPickers() = checkPicker("duckdb-brikk-native")

    fun testQuackDriverInNewAndRecentPickers() = checkPicker("duckdb-brikk-quack")

    private fun checkPicker(id: String) {
        val driver = checkNotNull(DatabaseDriverManager.getInstance().getDriver(id))
        assertSame(DuckdbDbms.DUCKDB_BRIKK, driver.forcedDbms)
        val expected = DuckdbDbms.DUCKDB_BRIKK.icon
        val manager = LocalDataSourceManager.getInstance(project)
        val properties = PropertiesComponent.getInstance(project)
        val key = "DatabaseDriversLRU"
        val previous = properties.getList(key)
        try {
            for (recent in listOf(false, true)) {
                properties.unsetValue(key)
                if (recent) DatabaseViewActions.addToLRUNames(project, 10, key, manager.getDriverKey(driver))
                val actions = manager.getCreateDataSourceActions(listOf(driver)) { error("The picker must not create a data source") }
                val entry = actions.filterNot { it is Separator }.single()
                assertSame("$id picker icon must match its data-source DBMS icon; recent=$recent", expected, entry.templatePresentation.icon)
            }
        } finally {
            if (previous == null) properties.unsetValue(key) else properties.setList(key, previous)
        }
    }
}
