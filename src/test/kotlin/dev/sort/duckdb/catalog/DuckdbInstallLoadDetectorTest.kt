package dev.sort.duckdb.catalog

import org.junit.Assert.*
import org.junit.Test

/** Baseline named cases exercise the actual shared observer profile, not a second detector. */
class DuckdbInstallLoadDetectorTest {
    private fun matches(sql: String?) = DuckdbExecutionObserverProfile.statements.detect(sql).any { it.rule.notifies }
    @Test fun `plain INSTALL matches`() = assertTrue(matches("INSTALL spatial"))
    @Test fun `lowercase load matches`() = assertTrue(matches("load icu"))
    @Test fun `FORCE INSTALL matches`() = assertTrue(matches("FORCE INSTALL x"))
    @Test fun `mixed case matches`() { assertTrue(matches("InStAlL spatial")); assertTrue(matches("Force  Install spatial")); assertTrue(matches("LoAd 'path/ext.duckdb_extension'")) }
    @Test fun `leading line comment is skipped`() = assertTrue(matches("-- setup\nINSTALL spatial"))
    @Test fun `leading block comment is skipped`() { assertTrue(matches("/* setup */ LOAD icu")); assertTrue(matches("/* one */ /* two */\n -- three\n FORCE INSTALL x")) }
    @Test fun `multi-statement script matches on the middle statement`() = assertTrue(matches("CREATE TABLE t(a int); INSTALL spatial; SELECT 1"))
    @Test fun `empty statements from repeated semicolons are tolerated`() = assertTrue(matches(";; ;\n; INSTALL spatial;"))
    @Test fun `INSTALL inside a string literal does not match`() { assertFalse(matches("SELECT 'INSTALL spatial'")); assertFalse(matches("SELECT 'please LOAD icu' FROM t")) }
    @Test fun `INSTALL-ish identifiers do not match`() {
        for (sql in listOf("SELECT * FROM install_log", "SELECT installer FROM t", "INSTALLER x", "install2 x", "LOADING dock", "load_extension('icu')")) assertFalse(sql, matches(sql))
    }
    @Test fun `UPDATE EXTENSIONS does not match`() { assertFalse(matches("UPDATE EXTENSIONS")); assertFalse(matches("update extensions;")) }
    @Test fun `plain statements do not match`() { for (sql in listOf("SELECT 1", "CREATE TABLE t(a int)", "/* INSTALL only */ SELECT 1", "-- INSTALL spatial\nSELECT 1")) assertFalse(sql, matches(sql)) }
    @Test fun `null and blank do not match`() { for (sql in listOf(null, "", " \n\t ", ";")) assertFalse(matches(sql)) }
    @Test fun `FORCE alone or FORCE plus other word does not match`() { assertFalse(matches("FORCE")); assertFalse(matches("FORCE CHECKPOINT")) }
}
