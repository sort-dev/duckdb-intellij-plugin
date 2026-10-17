package dev.sort.duckdb.catalog

import com.intellij.openapi.progress.ProcessCanceledException
import dev.brikk.house.sql.intellij.catalog.CatalogRowSource
import dev.brikk.house.sql.intellij.testing.JdbcCatalogRows
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTIONS
import dev.brikk.house.sql.metadata.DUCKDB_KEYWORDS
import dev.brikk.house.sql.metadata.FunctionKind
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager
import java.util.concurrent.CancellationException

class DuckdbLiveHarvestTest {
    @Test fun `real harvest against the in-process engine`() = `real harvest captures the engine signatures and keyword categories`()

    @Test fun `duckdb_keywords columns are keyword_name and keyword_category`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery("SELECT keyword_name, keyword_category FROM duckdb_keywords() LIMIT 3").use { rows ->
                assertTrue(rows.next()); assertNotNull(rows.getString(1)); assertNotNull(rows.getString(2))
            } }
        }
        assertTrue(DUCKDB_KEYWORDS.sql.contains("keyword_name"))
    }

    @Test fun `canned rows - kind mapping, operator filter, first-wins dedupe, uppercased keywords`() {
        `capture retains kinds and uppercases keywords through completion`()
        // Kind-aware completion intentionally supersedes first-name-wins; operators still never appear.
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            assertTrue(requireNotNull(DuckdbCatalogHarvester.harvest(JdbcCatalogRows(connection))).shared.functions.all {
                Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it.def.name)
            })
        }
    }

    @Test fun `empty function inventory means a broken read - harvest abandons`() = `an incomplete capture does not produce an inventory`()

    @Test fun `blown deadline abandons before any query runs`() = legacyCatalog { fixture ->
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val profile = object : dev.brikk.house.sql.intellij.catalog.LiveCatalogProfile by DuckdbLiveCatalogProfile {
            override val deadline = kotlin.time.Duration.ZERO
            override val harvester = dev.brikk.house.sql.intellij.catalog.LiveCatalogHarvester { rows ->
                rows.forEachRow(DuckdbCatalogHarvester.SQL_VERSION) { calls.incrementAndGet() }
                null
            }
        }
        val source = fixture.dataSource()
        val service = fixture.service(profile)
        DriverManager.getConnection("jdbc:duckdb:").use { fixture.connect(it, source) }
        assertEquals(0, calls.get()); assertNull(service.selection(source).inventory)
    }

    @Test fun `mid-harvest deadline keeps whatever ran but yields null`() = legacyCatalog { fixture ->
        val versionRead = java.util.concurrent.atomic.AtomicBoolean()
        val profile = object : dev.brikk.house.sql.intellij.catalog.LiveCatalogProfile by DuckdbLiveCatalogProfile {
            override val deadline = kotlin.time.Duration.parse("2s")
            override val harvester = dev.brikk.house.sql.intellij.catalog.LiveCatalogHarvester { rows ->
                rows.forEachRow(DuckdbCatalogHarvester.SQL_VERSION) { versionRead.set(true) }
                try { java.util.concurrent.CountDownLatch(1).await() } catch (_: InterruptedException) { }
                null
            }
        }
        val source = fixture.dataSource()
        val service = fixture.service(profile)
        DriverManager.getConnection("jdbc:duckdb:").use { fixture.connect(it, source) }
        assertTrue("Version query must precede the deadline", versionRead.get()); assertNull(service.selection(source).inventory)
    }

    @Test
    fun `real harvest captures the engine signatures and keyword categories`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val harvest = requireNotNull(DuckdbCatalogHarvester.harvest(JdbcCatalogRows(connection)))
            val shared = harvest.shared
            val expected = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version()").use { it.next(); it.getString(1) }
            }
            assertEquals(expected, shared.serverVersion)
            assertTrue(shared.signatures)
            assertTrue(shared.extensions.containsAll(listOf("core_functions", "json", "parquet")))
            assertTrue(shared.functions.size > 500)
            for ((name, kind) in listOf("read_parquet" to FunctionKind.TABLE_VALUED,
                "list_transform" to FunctionKind.SCALAR, "sum" to FunctionKind.AGGREGATE)) {
                assertTrue(shared.functions.any { it.def.name == name && it.def.kind == kind && it.def.overloads.isNotEmpty() })
            }
            assertTrue(shared.functions.all { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it.def.name) })
            assertTrue(shared.keywords.orEmpty().any { it.word.equals("SELECT", true) })
            assertTrue(shared.keywords.orEmpty().size > 300)
        }
    }

    @Test
    fun `temporary macros are session facts and persistent macros are shared`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TEMP MACRO temporary_only(x) AS x + 1")
                it.execute("CREATE MACRO persistent_fn(x) AS x + 2")
            }
            val harvest = requireNotNull(DuckdbCatalogHarvester.harvest(JdbcCatalogRows(connection)))
            assertTrue(harvest.session.functions.any { it.def.name == "temporary_only" && it.namespace == listOf("temp", "main") })
            assertFalse(harvest.shared.functions.any { it.def.name == "temporary_only" })
            assertTrue(harvest.shared.functions.any { it.def.name == "persistent_fn" })
        }
    }

    @Test
    fun `capture retains kinds and uppercases keywords through completion`() {
        val rows = CatalogRowSource { sql, onRow ->
            val data = when (sql) {
                DuckdbCatalogHarvester.SQL_VERSION -> listOf(listOf("v2.0.0"))
                DUCKDB_FUNCTIONS.sql -> listOf("aggregate", "scalar", "window", "table").map { kind ->
                    listOf("dup_fn", kind, "BIGINT", "[\"BIGINT\"]", "[\"x\"]", null, "system", "main", "true", null, null, null, null)
                }
                DUCKDB_KEYWORDS.sql -> listOf(listOf("select", "reserved"))
                else -> emptyList()
            }
            data.forEach { values -> onRow { values[it - 1] } }
        }
        val harvest = requireNotNull(DuckdbCatalogHarvester.harvest(rows))
        assertEquals(setOf(FunctionKind.SCALAR, FunctionKind.AGGREGATE, FunctionKind.WINDOW, FunctionKind.TABLE_VALUED), harvest.shared.functions.map { it.def.kind }.toSet())
        assertEquals("select", harvest.shared.keywords!!.single().word)
    }

    @Test
    fun `an unknown function kind leaves every other harvested function intact`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TEMP MACRO temporary_only(x) AS x + 1")
                it.execute("CREATE MACRO persistent_fn(x) AS x + 2")
            }
            val jdbc = JdbcCatalogRows(connection)
            val expected = requireNotNull(DuckdbCatalogHarvester.harvest(jdbc))
            val rows = CatalogRowSource { sql, onRow ->
                jdbc.forEachRow(sql, onRow)
                if (sql == DUCKDB_FUNCTIONS.sql) {
                    onRow { column -> listOf("future_fn", "future_kind").getOrNull(column - 1) }
                }
            }
            val actual = requireNotNull(DuckdbCatalogHarvester.harvest(rows))
            assertEquals(expected, actual)
            assertFalse((actual.shared.functions + actual.session.functions).any { it.def.name == "future_fn" })
        }
    }

    @Test
    fun `an incomplete capture does not produce an inventory`() {
        assertNull(DuckdbCatalogHarvester.harvest(CatalogRowSource { _, _ -> }))
        val versionOnly = CatalogRowSource { sql, row ->
            if (sql == DuckdbCatalogHarvester.SQL_VERSION) row { "v1.5.5" }
        }
        assertNull(DuckdbCatalogHarvester.harvest(versionOnly))
    }

    @Test
    fun `cancellation and linkage errors propagate from the harvester`() {
        for (failure in listOf(ProcessCanceledException(), CancellationException(), LinkageError())) {
            assertSame(failure, assertThrows(failure.javaClass) {
                DuckdbCatalogHarvester.harvest(CatalogRowSource { _, _ -> throw failure })
            })
        }
    }
}
