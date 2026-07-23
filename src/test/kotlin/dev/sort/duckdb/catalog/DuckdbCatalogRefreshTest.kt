package dev.sort.duckdb.catalog

import dev.sort.duckdb.sql.DuckdbFunctionCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * The refresh core minus the IDE ([DuckdbCatalogRefresh.harvestAndStore] + the balloon texts):
 * driven against the REAL in-process engine through the same [CatalogRowSource] seam the IDE-side
 * helper connection uses. The connection-acquisition layer itself ([DuckdbCatalogRefresh.harvestNow])
 * is IDE-bound by design and stays thin — everything it wraps is exercised here.
 */
class DuckdbCatalogRefreshTest {

    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("duckdb-refresh-test")
        DuckdbLiveCatalog.baseDirOverride = tempDir
        DuckdbLiveCatalog.clearForTests()
    }

    @After
    fun tearDown() {
        DuckdbLiveCatalog.baseDirOverride = null
        DuckdbLiveCatalog.clearForTests()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `harvestAndStore against the in-process engine refreshes memory and disk`() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val outcome = DuckdbCatalogRefresh.harvestAndStore("refresh-ds", JdbcRowSource(c))
            assertTrue("live engine must refresh: $outcome", outcome is DuckdbCatalogRefresh.Outcome.Refreshed)
            val entry = (outcome as DuckdbCatalogRefresh.Outcome.Refreshed).entry
            assertTrue("functions: ${entry.functions.size}", entry.functions.size > 500)
            // Stored for completion (memory)...
            assertEquals(entry, DuckdbLiveCatalog.entryFor("refresh-ds"))
            // ...and persisted (disk): a cold cache reloads the same harvest.
            DuckdbLiveCatalog.clearForTests()
            val reloaded = DuckdbLiveCatalog.entryFor("refresh-ds")
            assertNotNull("refresh must persist like the on-connect harvest", reloaded)
            assertEquals(entry.engineVersion, reloaded!!.engineVersion)
            assertEquals(entry.functions.size, reloaded.functions.size)
        }
    }

    @Test
    fun `refresh REPLACES a stale entry for the data source`() {
        DuckdbLiveCatalog.store(
            "refresh-ds",
            DuckdbLiveCatalog.Entry(
                engineVersion = "v0.0.1-stale",
                loadedExtensions = listOf("ghost"),
                functions = listOf(DuckdbFunctionCatalog.Fn("stale_fn", DuckdbFunctionCatalog.Kind.SCALAR)),
                keywords = setOf("SELECT"),
                harvestedAtMillis = 1L,
            ),
        )
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val outcome = DuckdbCatalogRefresh.harvestAndStore("refresh-ds", JdbcRowSource(c))
            assertTrue(outcome is DuckdbCatalogRefresh.Outcome.Refreshed)
        }
        val active = DuckdbLiveCatalog.entryFor("refresh-ds")!!
        assertTrue("stale version must be gone", active.engineVersion != "v0.0.1-stale")
        assertTrue("stale function must be gone", active.functions.none { it.name == "stale_fn" })
    }

    @Test
    fun `a throwing row source maps to HarvestFailed and keeps the previous cache`() {
        val previous = DuckdbLiveCatalog.Entry(
            engineVersion = "v1.2.3",
            loadedExtensions = emptyList(),
            functions = listOf(DuckdbFunctionCatalog.Fn("keep_me", DuckdbFunctionCatalog.Kind.SCALAR)),
            keywords = setOf("SELECT"),
            harvestedAtMillis = 1L,
        )
        DuckdbLiveCatalog.store("refresh-ds", previous)
        val boom = CatalogRowSource { _, _ -> throw IllegalStateException("connection lost") }
        assertEquals(DuckdbCatalogRefresh.Outcome.HarvestFailed, DuckdbCatalogRefresh.harvestAndStore("refresh-ds", boom))
        assertEquals("previous cache must survive a failed refresh", previous, DuckdbLiveCatalog.entryFor("refresh-ds"))
    }

    @Test
    fun `an abandoned harvest (empty inventory) maps to HarvestFailed and stores nothing`() {
        val empty = CatalogRowSource { _, _ -> } // every query yields zero rows -> harvester bails
        assertEquals(DuckdbCatalogRefresh.Outcome.HarvestFailed, DuckdbCatalogRefresh.harvestAndStore("refresh-ds", empty))
        assertNull(DuckdbLiveCatalog.entryFor("refresh-ds"))
    }

    @Test
    fun `success balloon lists version, count, and the loaded extensions`() {
        val entry = DuckdbLiveCatalog.Entry(
            engineVersion = "v1.5.5",
            loadedExtensions = listOf("core_functions", "json", "spatial"),
            functions = listOf(
                DuckdbFunctionCatalog.Fn("st_area", DuckdbFunctionCatalog.Kind.SCALAR),
                DuckdbFunctionCatalog.Fn("read_csv", DuckdbFunctionCatalog.Kind.TABLE),
            ),
            keywords = setOf("SELECT"),
            harvestedAtMillis = 1L,
        )
        assertEquals(
            "Catalog refreshed: DuckDB v1.5.5, 2 functions, extensions: core_functions, json, spatial",
            DuckdbCatalogRefresh.successText(entry),
        )
        // The extension list is the autoload story: a refresh after an autoloaded `spatial` is the
        // only place the user SEES it arrived, so the list must always be spelled out.
        assertTrue(DuckdbCatalogRefresh.successText(entry).contains("spatial"))
    }

    @Test
    fun `success balloon says none-beyond-core for an empty extension list`() {
        val entry = DuckdbLiveCatalog.Entry(
            engineVersion = "v1.5.5",
            loadedExtensions = emptyList(),
            functions = listOf(DuckdbFunctionCatalog.Fn("abs", DuckdbFunctionCatalog.Kind.SCALAR)),
            keywords = setOf("SELECT"),
            harvestedAtMillis = 1L,
        )
        assertEquals(
            "Catalog refreshed: DuckDB v1.5.5, 1 functions, extensions: none beyond core",
            DuckdbCatalogRefresh.successText(entry),
        )
    }

    @Test
    fun `failure texts name the failure stage briefly`() {
        assertEquals(
            "No DuckDB (Brikk) data source to refresh.",
            DuckdbCatalogRefresh.failureText(DuckdbCatalogRefresh.Outcome.NoDataSource),
        )
        assertEquals(
            "Could not connect to the data source: no driver",
            DuckdbCatalogRefresh.failureText(DuckdbCatalogRefresh.Outcome.ConnectFailed("no driver")),
        )
        assertEquals(
            "Could not connect to the data source.",
            DuckdbCatalogRefresh.failureText(DuckdbCatalogRefresh.Outcome.ConnectFailed(null)),
        )
        assertEquals(
            "Connected, but reading the catalog failed; keeping the previous one.",
            DuckdbCatalogRefresh.failureText(DuckdbCatalogRefresh.Outcome.HarvestFailed),
        )
    }
}
