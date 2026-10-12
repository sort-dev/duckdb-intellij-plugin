package dev.sort.duckdb

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.assertIntrospectorRoutesToGeneric
import dev.sort.duckdb.DuckdbDbms.DUCKDB_BRIKK

class DuckdbIntrospectorRoutingTest : BasePlatformTestCase() {
    fun testRoutesToGeneric() {
        assertIntrospectorRoutesToGeneric(DUCKDB_BRIKK)
    }
}
