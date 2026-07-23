package dev.sort.duckdb.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the observer's statement-head discipline: INSTALL / FORCE INSTALL / LOAD as a statement's
 * first meaningful word triggers; the same words as substrings, identifiers, or string-literal
 * content never do. No parse — split on ';', skip whitespace/comments, match the head word only.
 */
class DuckdbInstallLoadDetectorTest {

    private fun matches(sql: String?) = DuckdbInstallLoadDetector.triggersCatalogChange(sql)

    @Test
    fun `plain INSTALL matches`() = assertTrue(matches("INSTALL spatial"))

    @Test
    fun `lowercase load matches`() = assertTrue(matches("load icu"))

    @Test
    fun `FORCE INSTALL matches`() = assertTrue(matches("FORCE INSTALL x"))

    @Test
    fun `mixed case matches`() {
        assertTrue(matches("InStAlL spatial"))
        assertTrue(matches("Force  Install spatial"))
        assertTrue(matches("LoAd 'path/ext.duckdb_extension'"))
    }

    @Test
    fun `leading line comment is skipped`() =
        assertTrue(matches("-- set up the extension\nINSTALL spatial"))

    @Test
    fun `leading block comment is skipped`() {
        assertTrue(matches("/* setup */ LOAD icu"))
        assertTrue(matches("/* one */ /* two */\n  -- three\n  FORCE INSTALL x"))
    }

    @Test
    fun `multi-statement script matches on the middle statement`() =
        assertTrue(matches("CREATE TABLE t(a int); INSTALL spatial; SELECT 1"))

    @Test
    fun `empty statements from repeated semicolons are tolerated`() =
        assertTrue(matches(";;  ;\n; INSTALL spatial;"))

    @Test
    fun `INSTALL inside a string literal does not match`() {
        assertFalse(matches("SELECT 'INSTALL spatial'"))
        assertFalse(matches("SELECT 'please LOAD icu' FROM t"))
    }

    @Test
    fun `INSTALL-ish identifiers do not match`() {
        assertFalse(matches("SELECT * FROM install_log"))
        assertFalse(matches("SELECT installer FROM t"))
        // Word-boundary at the head itself: longer words sharing the prefix are not INSTALL/LOAD.
        assertFalse(matches("INSTALLER x"))
        assertFalse(matches("install2 x"))
        assertFalse(matches("LOADING dock"))
        assertFalse(matches("load_extension('icu')")) // scalar-function call, head word is load_extension
    }

    @Test
    fun `UPDATE EXTENSIONS does not match`() {
        // UPDATE EXTENSIONS refreshes repo metadata only — it LOADs nothing, so the live
        // function inventory is unchanged and a re-harvest would be noise.
        assertFalse(matches("UPDATE EXTENSIONS"))
        assertFalse(matches("update extensions;"))
    }

    @Test
    fun `plain statements do not match`() {
        assertFalse(matches("SELECT 1"))
        assertFalse(matches("CREATE TABLE t(a int)"))
        assertFalse(matches("/* INSTALL in a comment only */ SELECT 1"))
        assertFalse(matches("-- INSTALL spatial\nSELECT 1"))
    }

    @Test
    fun `null and blank do not match`() {
        assertFalse(matches(null))
        assertFalse(matches(""))
        assertFalse(matches("   \n\t "))
        assertFalse(matches(";"))
    }

    @Test
    fun `FORCE alone or FORCE plus other word does not match`() {
        assertFalse(matches("FORCE"))
        assertFalse(matches("FORCE CHECKPOINT"))
    }
}
