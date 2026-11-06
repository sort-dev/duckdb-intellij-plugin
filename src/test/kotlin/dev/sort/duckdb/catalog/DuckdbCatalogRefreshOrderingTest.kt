package dev.sort.duckdb.catalog

import dev.brikk.house.sql.intellij.catalog.CatalogSource
import dev.brikk.house.sql.intellij.catalog.LiveCatalogHarvester
import dev.brikk.house.sql.intellij.catalog.LiveCatalogProfile
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/** Real shared service/interceptor/harvester; deterministic interleavings on test-owned databases. */
class DuckdbCatalogRefreshOrderingTest {
    @Test
    fun `forget during a harvest prevents late catalog and disk restoration`() = legacyCatalog { fixture ->
        val source = fixture.dataSource()
        lateinit var service: ParityCatalogService
        val profile = object : LiveCatalogProfile by DuckdbLiveCatalogProfile {
            override val harvester = LiveCatalogHarvester { rows ->
                requireNotNull(DuckdbCatalogHarvester.harvest(rows)).also { service.forget(source.uniqueId) }
            }
        }
        service = fixture.service(profile)
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { it.execute("CREATE MACRO forgotten_catalog_fn(x) AS x") }
            fixture.connect(connection, source)
        }
        assertEquals(CatalogSource.Bundled, service.selection(source).source)
        assertNull(service.selection(source).inventory)
        val reopened = fixture.service(profile)
        assertEquals(CatalogSource.Bundled, reopened.selection(source).source)
        assertNull("A late result must not recreate the forgotten disk catalog", reopened.selection(source).inventory)
    }

    @Test
    fun `newer successful harvest cannot be overwritten by an older completion`() = orderedHarvest(false)

    @Test
    fun `a newer failed harvest does not fence out an older successful read`() = orderedHarvest(true)

    private fun orderedHarvest(newerFails: Boolean) = legacyCatalog { fixture ->
        val source = fixture.dataSource()
        DriverManager.getConnection("jdbc:duckdb:").use { older ->
            DriverManager.getConnection("jdbc:duckdb:").use { newer ->
                older.createStatement().use { it.execute("CREATE MACRO older_catalog_fn(x) AS x") }
                newer.createStatement().use { it.execute("CREATE MACRO newer_catalog_fn(x) AS x") }
                val calls = AtomicInteger()
                val profile = object : LiveCatalogProfile by DuckdbLiveCatalogProfile {
                    override val harvester = LiveCatalogHarvester { rows ->
                        val order = calls.incrementAndGet()
                        if (order == 2 && newerFails) null else {
                            requireNotNull(DuckdbCatalogHarvester.harvest(rows)).also {
                                if (order == 1) fixture.connect(newer, source)
                            }
                        }
                    }
                }
                val service = fixture.service(profile)
                fixture.connect(older, source)
                assertEquals("The newer read must finish before the older read records", 2, calls.get())
                val expected = if (newerFails) "older_catalog_fn" else "newer_catalog_fn"
                val rejected = if (newerFails) "newer_catalog_fn" else "older_catalog_fn"
                val inventory = requireNotNull(service.selection(source).inventory)
                assertTrue(inventory.functions.any { it.def.name == expected })
                assertFalse(inventory.functions.any { it.def.name == rejected })
                val persisted = requireNotNull(fixture.service(profile).selection(source).inventory)
                assertEquals("Disk fallback must keep the same winning complete inventory", inventory, persisted)
            }
        }
    }
}
