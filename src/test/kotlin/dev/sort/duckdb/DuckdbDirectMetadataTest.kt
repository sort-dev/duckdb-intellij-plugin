package dev.sort.duckdb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * Proves the DIRECT-metadata path headlessly: an in-process DuckDB answers the catalog queries the
 * plugin will later run through the user's data source — function inventory, keywords, and
 * PREPARE-as-authoritative-validator (the version-matched analog of Doris's embedded
 * fe-sql-parser). Runs against the test-only duckdb_jdbc dependency; the shipped plugin bundles
 * no engine.
 */
class DuckdbDirectMetadataTest {

    private fun withDuck(block: (java.sql.Connection) -> Unit) =
        DriverManager.getConnection("jdbc:duckdb:").use(block)

    @Test
    fun `duckdb_functions inventory is queryable and large`() = withDuck { c ->
        c.createStatement().executeQuery(
            "SELECT count(DISTINCT function_name) FROM duckdb_functions()",
        ).use { rs ->
            rs.next()
            val n = rs.getInt(1)
            assertTrue("expected a rich builtin inventory, got $n", n > 500)
        }
    }

    @Test
    fun `keywords inventory is queryable`() = withDuck { c ->
        c.createStatement().executeQuery("SELECT count(*) FROM duckdb_keywords()").use { rs ->
            rs.next()
            assertTrue(rs.getInt(1) > 100)
        }
    }

    @Test
    fun `PREPARE works as an authoritative syntax validator`() = withDuck { c ->
        // Valid DuckDB-only syntax must PREPARE (no execution, no data needed beyond a table).
        c.createStatement().execute("CREATE TABLE t (a INT, b INT, c INT)")
        c.createStatement().execute("PREPARE ok AS SELECT * EXCLUDE (c) FROM t GROUP BY ALL")
        // Broken SQL must throw with a positioned parser message.
        val err = runCatching {
            c.createStatement().execute("PREPARE bad AS SELECT FROM WHERE")
        }.exceptionOrNull()
        assertTrue("expected a parse error, got $err", err != null && err.message!!.contains("Parser Error"))
    }
}
