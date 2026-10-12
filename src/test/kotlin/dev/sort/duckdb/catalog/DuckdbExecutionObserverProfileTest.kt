package dev.sort.duckdb.catalog

import dev.brikk.house.sql.intellij.catalog.StatementEffect
import org.junit.Assert.*
import org.junit.Test

class DuckdbExecutionObserverProfileTest {
    private val detector = DuckdbExecutionObserverProfile.statements

    @Test
    fun `install and load notify but macro DDL does not`() {
        for (sql in listOf("INSTALL spatial", "force install spatial", "/* setup */ LOAD 'path/ext.duckdb_extension'")) {
            assertTrue(detector.detect(sql).single().rule.notifies)
        }
        for (sql in listOf("CREATE MACRO persistent(x) AS x", "CREATE OR REPLACE TEMP MACRO local(x) AS x", "DROP MACRO local")) {
            assertFalse(detector.detect(sql).single().rule.notifies)
        }
        assertTrue((detector.detect("LOAD fts").single().effect as StatementEffect.FunctionInventory).sessionFacts)
        assertTrue((detector.detect("CREATE TEMP MACRO local(x) AS x").single().effect as StatementEffect.FunctionInventory).sessionFacts)
        assertNull(DuckdbExecutionObserverProfile.autoIntrospect)
    }

    @Test
    fun `only whole statement heads trigger inventory changes`() {
        for (sql in listOf(null, "", "SELECT 'INSTALL spatial; LOAD fts'", "SELECT installer FROM t", "INSTALLER x",
            "LOADING x", "FORCE CHECKPOINT", "UPDATE EXTENSIONS", "/* LOAD fts */ SELECT 1")) {
            assertTrue(sql, detector.detect(sql).isEmpty())
        }
        assertEquals(2, detector.detect(";; SELECT 1; /* nested /* setup */ */ INSTALL fts; LOAD fts;").size)
    }
}
