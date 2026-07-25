package dev.sort.duckdb.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the object-tree half of the observer's statement-head discipline: ATTACH / DETACH as a
 * statement's first meaningful word triggers a namespace re-listing; the same words as substrings,
 * identifiers, column names or string-literal content never do — and neither do the statements we
 * deliberately leave to the platform (`USE`, ordinary DDL).
 */
class DuckdbAttachDetectorTest {

    private fun matches(sql: String?) = DuckdbAttachDetector.triggersNamespaceChange(sql)

    @Test
    fun `plain ATTACH matches`() = assertTrue(matches("ATTACH 'side.duckdb' AS side"))

    @Test
    fun `bare ATTACH without alias matches`() = assertTrue(matches("ATTACH 'side.duckdb'"))

    @Test
    fun `ATTACH IF NOT EXISTS matches`() =
        assertTrue(matches("ATTACH IF NOT EXISTS 'side.duckdb' AS side"))

    @Test
    fun `ATTACH DATABASE matches`() = assertTrue(matches("ATTACH DATABASE 'side.duckdb' AS side"))

    @Test
    fun `ATTACH with options matches`() =
        assertTrue(matches("ATTACH 'side.duckdb' AS side (READ_ONLY)"))

    @Test
    fun `remote attach forms match`() {
        assertTrue(matches("ATTACH 'ducklake:metadata.db' AS lake"))
        assertTrue(matches("ATTACH 'postgres:dbname=app host=db.example.com' AS pg (TYPE postgres)"))
    }

    @Test
    fun `DETACH matches`() {
        assertTrue(matches("DETACH side"))
        assertTrue(matches("DETACH DATABASE side"))
    }

    @Test
    fun `mixed case matches`() {
        assertTrue(matches("AtTaCh 'x.duckdb' AS x"))
        assertTrue(matches("detach x"))
    }

    @Test
    fun `leading comments are skipped`() {
        assertTrue(matches("-- bring in the lake\nATTACH 'ducklake:m.db' AS lake"))
        assertTrue(matches("/* setup */ DETACH lake"))
    }

    @Test
    fun `multi-statement script matches on the middle statement`() =
        assertTrue(matches("SELECT 1; ATTACH 'x.duckdb' AS x; SELECT 2"))

    @Test
    fun `install then attach both detected by their own detectors`() {
        val script = "INSTALL ducklake; ATTACH 'ducklake:m.db' AS lake"
        assertTrue(DuckdbInstallLoadDetector.triggersCatalogChange(script))
        assertTrue(matches(script))
    }

    @Test
    fun `word boundaries hold`() {
        assertFalse(matches("SELECT attach_count FROM t"))
        assertFalse(matches("SELECT * FROM attachments"))
        assertFalse(matches("SELECT 'ATTACH x' AS s"))
    }

    @Test
    fun `statements we leave to the platform do not match`() {
        assertFalse(matches("USE side_db"))
        assertFalse(matches("CREATE SCHEMA s"))
        assertFalse(matches("CREATE TABLE t(a INTEGER)"))
        assertFalse(matches("DROP TABLE t"))
        assertFalse(matches("INSTALL spatial"))
        assertFalse(matches("LOAD icu"))
    }

    @Test
    fun `blank and null do not match`() {
        assertFalse(matches(null))
        assertFalse(matches(""))
        assertFalse(matches("   \n  "))
    }

    @Test
    fun `non-keyword head does not match`() {
        assertFalse(matches("(ATTACH 'x')"))
        assertFalse(matches("'ATTACH'"))
    }
}
