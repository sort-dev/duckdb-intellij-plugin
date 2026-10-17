package dev.sort.duckdb.validate

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class DuckdbEngineLocationTest : BasePlatformTestCase() {
    private fun location() = DuckdbEngineLocation(project).also { Disposer.register(testRootDisposable, it) }

    fun testRepeatedPassesLocateOnceAndDatasourceChangesInvalidate() {
        val location = location()
        var calls = 0
        val locate = { calls++; Path.of("driver-$calls.jar") }
        val first = location.engineJar(locate)
        assertEquals(first, location.engineJar(locate))
        assertEquals(1, calls)

        val source = LocalDataSource.create("locator-probe", "org.duckdb.DuckDBDriver", "jdbc:duckdb:", "")
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(source)
        try {
            assertEquals(Path.of("driver-2.jar"), location.engineJar(locate))
            assertEquals(2, calls)
            manager.fireDataSourceUpdated(source)
            assertEquals(Path.of("driver-3.jar"), location.engineJar(locate))
            assertEquals(3, calls)
        } finally {
            manager.removeDataSource(source)
        }
    }

    fun testMissingEngineCachedUntilDriverChange() {
        val location = location()
        var calls = 0
        val missing = { calls++; null }
        assertNull(location.engineJar(missing))
        assertNull(location.engineJar(missing))
        assertEquals(1, calls)
        val drivers = DatabaseDriverManager.getInstance()
        val driver = requireNotNull(drivers.getDriver("duckdb-brikk-native")).copy("duckdb-cache-probe", false)
        drivers.updateDriver(driver)
        try {
            assertNull(location.engineJar(missing))
            assertEquals(2, calls)
        } finally {
            drivers.removeDriver(driver)
        }
    }
}
