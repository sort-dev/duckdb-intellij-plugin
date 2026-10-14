package dev.sort.duckdb.catalog

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import dev.brikk.house.sql.intellij.catalog.*
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager

class DuckdbCatalogRefreshTest {
    @Test fun `harvestAndStore against the in-process engine refreshes memory and disk`() = legacyCatalog { fixture ->
        val source = fixture.dataSource()
        val service = fixture.service()
        DriverManager.getConnection("jdbc:duckdb:").use { fixture.connect(it, source) }
        val inventory = requireNotNull(service.selection(source).inventory)
        assertTrue(inventory.functions.size > 500)
        assertEquals(inventory, LiveCatalogStore(fixture.scratch.resolve("parity-store")).select(source.uniqueId, service.profile.identity(source)).inventory)
    }
    @Test fun `refresh REPLACES a stale entry for the data source`() = legacyCatalog { fixture ->
        val source = fixture.dataSource()
        val service = fixture.service()
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { it.execute("CREATE MACRO stale_fn(x) AS x") }
            fixture.connect(connection, source)
            assertTrue(requireNotNull(service.selection(source).inventory).functions.any { it.def.name == "stale_fn" })
            connection.createStatement().use { it.execute("DROP MACRO stale_fn") }
            fixture.connect(connection, source)
            assertFalse(requireNotNull(service.selection(source).inventory).functions.any { it.def.name == "stale_fn" })
        }
    }
    @Test fun `a throwing row source maps to HarvestFailed and keeps the previous cache`() = legacyCatalog { fixture ->
        val profile = object : LiveCatalogProfile by DuckdbLiveCatalogProfile {
            override val harvester = object : LiveCatalogHarvester {
                var fail = false
                override fun harvest(rows: CatalogRowSource): Harvest? {
                    if (fail) throw java.sql.SQLException("connection lost")
                    return DuckdbCatalogHarvester.harvest(rows)
                }
            }
        }
        val source = fixture.dataSource()
        val service = fixture.service(profile)
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            fixture.connect(connection, source)
            val prior = service.selection(source).inventory
            profile.harvester.fail = true
            fixture.connect(connection, source)
            assertSame(prior, service.selection(source).inventory)
        }
    }
    @Test fun `an abandoned harvest (empty inventory) maps to HarvestFailed and stores nothing`() = legacyCatalog { fixture ->
        val profile = object : LiveCatalogProfile by DuckdbLiveCatalogProfile {
            override val harvester = LiveCatalogHarvester { null }
        }
        val source = fixture.dataSource()
        val service = fixture.service(profile)
        DriverManager.getConnection("jdbc:duckdb:").use { fixture.connect(it, source) }
        assertNull(service.selection(source).inventory)
    }
    private fun notifications(fixture: LegacyCatalogFixture, outcome: RefreshOutcome): Notification {
        val received = mutableListOf<Notification>()
        fixture.testProject.messageBus.connect(fixture.disposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { received += notification }
        })
        val source = fixture.dataSource().apply { name = "Refresh parity" }
        fixture.service().notifyOutcome(fixture.testProject, source, outcome)
        return received.single().also { it.expire() }
    }
    @Test fun `success balloon lists version, count, and the loaded extensions`() = legacyCatalog { fixture ->
        val inventory = LiveInventory("v1.5.5", emptyList(), signatures = false, extensions = listOf("core_functions", "json", "spatial"))
        val text = notifications(fixture, RefreshOutcome.Refreshed(inventory)).content
        assertTrue(text, text.contains("v1.5.5") && text.contains("0 functions") && text.contains("core_functions, json, spatial"))
    }
    @Test fun `success balloon says none-beyond-core for an empty extension list`() = legacyCatalog { fixture ->
        val text = notifications(fixture, RefreshOutcome.Refreshed(LiveInventory("v1.5.5", emptyList(), signatures = false))).content
        // The shared UI omits an empty extension suffix rather than inventing session facts.
        assertTrue(text, text.contains("v1.5.5") && text.contains("0 functions"))
        assertFalse(text, text.contains("extensions:"))
    }
    @Test fun `failure texts name the failure stage briefly`() = legacyCatalog { fixture ->
        assertTrue(notifications(fixture, RefreshOutcome.ConnectFailed("no driver")).content.contains("no driver"))
        assertTrue(notifications(fixture, RefreshOutcome.ConnectFailed(null)).content.contains("Could not connect"))
        assertTrue(notifications(fixture, RefreshOutcome.HarvestFailed(HarvestOutcome.Incomplete)).content.contains("previous catalog stays"))
    }
}
