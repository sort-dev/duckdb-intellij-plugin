package dev.sort.duckdb

import com.intellij.database.actions.DatabaseViewActions
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.artifacts.DatabaseArtifactManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DuckdbDriverPickerIconTest : BasePlatformTestCase() {
    fun testEmbeddedDriverInNewAndRecentPickers() = checkPicker("duckdb-brikk-native")

    fun testQuackDriverInNewAndRecentPickers() = checkPicker("duckdb-brikk-quack")

    fun testQuackDriverUsesPreferredArtifact() {
        val driver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-quack"))
        val artifact = checkNotNull(DatabaseArtifactManager.resolveVersion(driver, driver.artifacts.single()))
        assertEquals("The IDE's default rolling Quack artifact must use the current release", "0.11.1", artifact.version.toString())
        assertTrue(artifact.items.toString(), artifact.items.any { it.url == "dev.brikk.duckdb:quack-jdbc:0.11.1" })
        val config = requireNotNull(javaClass.getResource("/config/duckdb-brikk-artifacts.xml")).readText()
        for (version in listOf("0.11.0", "0.8.0", "0.5.0", "0.4.0", "0.3.0")) {
            assertTrue("Older Quack $version must remain selectable", config.contains("<version version=\"$version\">"))
        }
    }

    fun testEmbeddedDriverDefaultsToGrammarCapableDuckdb2() {
        val driver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native"))
        val artifact = checkNotNull(DatabaseArtifactManager.resolveVersion(driver, driver.artifacts.single()))
        assertEquals("Registered grammar profiles require DuckDB 2.0", "2.0.0.0", artifact.version.toString())
        assertTrue(artifact.items.toString(), artifact.items.any { it.url == "org.duckdb:duckdb_jdbc:2.0.0.0" })
        val config = requireNotNull(javaClass.getResource("/config/duckdb-brikk-artifacts.xml")).readText()
        assertTrue("The older JDBC release must remain selectable", config.contains("<version version=\"1.5.5.0\">"))
    }

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
