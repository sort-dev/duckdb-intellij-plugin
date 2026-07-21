package dev.sort.duckdb

import dev.brikk.ducklake.slt.RecordKind
import dev.brikk.ducklake.slt.SltExpander
import dev.brikk.ducklake.slt.SltParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the slt-format artifact (dev.brikk.ducklake:slt-format) against the census-harvest
 * contract (API request 2026-07-21): parse → expand → flat [dev.brikk.ducklake.slt.ConcreteRecord]s
 * with provenance; loop/template expansion; skipif/onlyif resolution; expected-error metadata.
 */
class SltHarvestSmokeTest {

    private val sample = """
        # a comment
        statement ok
        CREATE TABLE t (a INT);

        statement error
        SELECT FROM WHERE
        ----
        Parser Error

        query I rowsort
        SELECT a FROM t;
        ----

        loop i 0 3

        statement ok
        INSERT INTO t VALUES (${'$'}{i});

        endloop

        skipif duckdb
        statement ok
        THIS SHOULD BE SKIPPED FOR DUCKDB;

        onlyif duckdb
        statement ok
        SELECT 1 FROM t;
    """.trimIndent()

    @Test
    fun `parse and expand produce flat concrete records`() {
        val file = SltParser.parse("smoke.test", sample)
        val records = SltExpander.expand(file, "duckdb")

        val sqls = records.map { it.sql.trim() }
        // loop expanded: three INSERTs with the variable substituted
        assertTrue(
            "loop must expand with substitution: $sqls",
            sqls.containsAll(listOf(
                "INSERT INTO t VALUES (0);", "INSERT INTO t VALUES (1);", "INSERT INTO t VALUES (2);",
            )),
        )
        // skipif duckdb honored; onlyif duckdb kept
        assertTrue("skipif duckdb must drop the record", sqls.none { it.contains("SHOULD BE SKIPPED") })
        assertTrue("onlyif duckdb must keep the record", sqls.any { it == "SELECT 1 FROM t;" })
        // record kinds + expected-error metadata carried through
        val err = records.first { it.sql.contains("SELECT FROM WHERE") }
        assertEquals(RecordKind.ERROR, err.kind)
        assertEquals("Parser Error", err.expectedError?.trim())
        assertEquals(RecordKind.QUERY, records.first { it.sql.startsWith("SELECT a FROM t") }.kind)
        // provenance survives expansion
        assertTrue("records must carry provenance", records.all { it.file == "smoke.test" && it.line > 0 })
    }
}
