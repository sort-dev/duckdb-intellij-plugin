package dev.sort.duckdb.catalog

import dev.sort.duckdb.sql.DuckdbFunctionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.sql.DriverManager

/**
 * Proves the harvest logic against the REAL in-process engine (the same code path the IDE-side
 * RemoteRowSource drives — [CatalogRowSource] is the seam) plus the parse/deadline behavior over
 * canned rows. Answers PLAN.md Stage 4b's keywords-column question with the engine itself.
 */
/** The test-side [CatalogRowSource]: a plain java.sql.ResultSet drives the shared harvest code. */
internal class JdbcRowSource(private val connection: java.sql.Connection) : CatalogRowSource {
    override fun forEachRow(sql: String, onRow: (col: (Int) -> String?) -> Unit) {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                while (rs.next()) onRow { i -> rs.getString(i) }
            }
        }
    }
}

class DuckdbLiveHarvestTest {

    private fun withDuck(block: (java.sql.Connection) -> Unit) =
        DriverManager.getConnection("jdbc:duckdb:").use(block)

    /** Canned rows keyed by SQL, recording execution order (deadline tests read it). */
    private class FakeRows(private val data: Map<String, List<List<String?>>>) : CatalogRowSource {
        val executed = ArrayList<String>()
        override fun forEachRow(sql: String, onRow: (col: (Int) -> String?) -> Unit) {
            executed.add(sql)
            for (row in data[sql].orEmpty()) onRow { i -> row.getOrNull(i - 1) }
        }
    }

    @Test
    fun `real harvest against the in-process engine`() = withDuck { c ->
        val entry = DuckdbCatalogHarvester.harvest(JdbcRowSource(c))
        assertNotNull("harvest against a live engine must succeed", entry)
        entry!!
        // Version-exact against the engine's own answer (survives duckdb_jdbc bumps).
        val expected = c.createStatement().executeQuery("SELECT version()").use { it.next(); it.getString(1) }
        assertEquals(expected, entry.engineVersion)
        // 1.5.5 jdbc statically links these; the list is our sorted-set cache identity.
        assertTrue("loaded: ${entry.loadedExtensions}", entry.loadedExtensions.containsAll(listOf("core_functions", "json", "parquet")))
        assertEquals(entry.loadedExtensions.sorted(), entry.loadedExtensions)
        val kinds = entry.functions.associate { it.name to it.kind }
        assertEquals(DuckdbFunctionCatalog.Kind.TABLE, kinds["read_parquet"])
        assertEquals(DuckdbFunctionCatalog.Kind.SCALAR, kinds["list_transform"])
        assertEquals(DuckdbFunctionCatalog.Kind.AGGREGATE, kinds["sum"])
        assertTrue("functions: ${entry.functions.size}", entry.functions.size > 500)
        assertTrue("operator names must be filtered", entry.functions.all { DuckdbFunctionCatalog.isCompletableName(it.name) })
        assertTrue(entry.keywords.contains("SELECT"))
        assertTrue("keywords: ${entry.keywords.size}", entry.keywords.size > 300)
    }

    @Test
    fun `duckdb_keywords columns are keyword_name and keyword_category`() = withDuck { c ->
        // The column-name question, answered by the engine: both exist, and SQL_KEYWORDS reads
        // keyword_name (a wrong name would throw a Binder Error right here).
        c.createStatement().executeQuery(
            "SELECT keyword_name, keyword_category FROM duckdb_keywords() LIMIT 3",
        ).use { rs ->
            assertTrue(rs.next())
            assertNotNull(rs.getString(1))
            assertNotNull(rs.getString(2))
        }
        assertTrue(DuckdbCatalogHarvester.SQL_KEYWORDS.contains("keyword_name"))
    }

    @Test
    fun `canned rows - kind mapping, operator filter, first-wins dedupe, uppercased keywords`() {
        val rows = FakeRows(
            mapOf(
                DuckdbCatalogHarvester.SQL_VERSION to listOf(listOf("v1.3.0")),
                DuckdbCatalogHarvester.SQL_LOADED_EXTENSIONS to listOf(listOf("json")),
                DuckdbCatalogHarvester.SQL_FUNCTIONS to listOf(
                    listOf("!~~", "scalar"), // operator -> not completable, filtered
                    listOf("dup_fn", "aggregate"), // sorted (1,2): first type wins…
                    listOf("dup_fn", "scalar"), // …this one is dropped
                    listOf("read_csv", "table"),
                    listOf("odd", "somenewtype"), // unknown function_type -> OTHER
                ),
                DuckdbCatalogHarvester.SQL_KEYWORDS to listOf(listOf("select"), listOf("pivot")),
            ),
        )
        val entry = DuckdbCatalogHarvester.harvest(rows)
        assertNotNull(entry)
        entry!!
        assertEquals("v1.3.0", entry.engineVersion)
        assertEquals(listOf("json"), entry.loadedExtensions)
        assertEquals(
            mapOf(
                "dup_fn" to DuckdbFunctionCatalog.Kind.AGGREGATE,
                "read_csv" to DuckdbFunctionCatalog.Kind.TABLE,
                "odd" to DuckdbFunctionCatalog.Kind.OTHER,
            ),
            entry.functions.associate { it.name to it.kind },
        )
        assertEquals(setOf("SELECT", "PIVOT"), entry.keywords)
    }

    @Test
    fun `empty function inventory means a broken read - harvest abandons`() {
        val rows = FakeRows(
            mapOf(
                DuckdbCatalogHarvester.SQL_VERSION to listOf(listOf("v1.3.0")),
                DuckdbCatalogHarvester.SQL_KEYWORDS to listOf(listOf("select")),
            ),
        )
        assertNull(DuckdbCatalogHarvester.harvest(rows))
    }

    @Test
    fun `blown deadline abandons before any query runs`() {
        val ticks = longArrayOf(0, 10_000) // start, then the first over-budget check
        var i = 0
        val clock = { ticks[minOf(i, ticks.size - 1)].also { i++ } }
        val rows = CatalogRowSource { sql, _ -> fail("no query may run after the deadline: $sql") }
        assertNull(DuckdbCatalogHarvester.harvest(rows, deadlineMillis = 5_000, clock = clock))
    }

    @Test
    fun `mid-harvest deadline keeps whatever ran but yields null`() {
        val ticks = longArrayOf(0, 100, 10_000) // start, pre-version check ok, then blown
        var i = 0
        val clock = { ticks[minOf(i, ticks.size - 1)].also { i++ } }
        val rows = FakeRows(mapOf(DuckdbCatalogHarvester.SQL_VERSION to listOf(listOf("v1.3.0"))))
        assertNull(DuckdbCatalogHarvester.harvest(rows, deadlineMillis = 5_000, clock = clock))
        assertEquals(listOf(DuckdbCatalogHarvester.SQL_VERSION), rows.executed)
    }
}
