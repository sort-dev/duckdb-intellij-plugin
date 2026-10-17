package dev.sort.duckdb

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.assertIntrospectorRoutesToGeneric
import dev.sort.duckdb.DuckdbDbms.DUCKDB_BRIKK

class DuckdbIntrospectorRoutingTest : BasePlatformTestCase() {
    fun testRoutesToGeneric() {
        assertIntrospectorRoutesToGeneric(DUCKDB_BRIKK)
        val type = Class.forName("com.intellij.database.dialects.generic.introspector.jdbc.GenericJdbcSourceLoader")
        // The loader's base class moved in 263; its public EP is still inherited by the generic loader.
        val ep = type.getField("EP").get(null)
        val selected = ep.javaClass.getMethod("forDbms", com.intellij.database.Dbms::class.java).invoke(ep, DUCKDB_BRIKK)
        assertEquals(type.name, selected.javaClass.name)
    }
}
