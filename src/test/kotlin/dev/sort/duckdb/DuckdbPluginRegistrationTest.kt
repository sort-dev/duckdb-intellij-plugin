package dev.sort.duckdb

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.PluginRegistrationContract

class DuckdbPluginRegistrationTest : BasePlatformTestCase() {
    fun testInstalledRegistrations() {
        PluginRegistrationContract("dev.sort.duckdb-intellij-plugin", "dev.sort.duckdb").check(project)
    }
}
