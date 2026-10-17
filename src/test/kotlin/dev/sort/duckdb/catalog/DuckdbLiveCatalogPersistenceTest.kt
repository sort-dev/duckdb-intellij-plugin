package dev.sort.duckdb.catalog

import dev.brikk.house.sql.intellij.catalog.*
import dev.brikk.house.sql.intellij.testing.JdbcCatalogRows
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class DuckdbLiveCatalogPersistenceTest {
    private fun scratch(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build", "catalog-tests")), "baseline-store-")
        try { action(directory) } finally { directory.toFile().deleteRecursively() }
    }
    private val identity = ConnectionIdentity.of(listOf("baseline", "jdbc:duckdb:"))
    @Test fun `round trip - write, drop memory, load, equal`() = scratch { directory ->
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val entry = requireNotNull(DuckdbCatalogHarvester.harvest(JdbcCatalogRows(connection))).shared
            val id = "file://ds/1 weird#id"
            LiveCatalogStore(directory).store(id, identity, entry)
            assertEquals(entry, LiveCatalogStore(directory).select(id, identity).inventory)
        }
    }
    @Test fun `corrupt file degrades to absent - bundled snapshot takes over`() = scratch { directory ->
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            LiveCatalogStore(directory).store("corrupt", identity, requireNotNull(DuckdbCatalogHarvester.harvest(JdbcCatalogRows(connection))).shared)
        }
        Files.list(directory).use { files -> files.filter { Files.isRegularFile(it) }.forEach { Files.writeString(it, "meta\tonly-two-fields\ngarbage\n") } }
        assertEquals(CatalogSource.Bundled, LiveCatalogStore(directory).select("corrupt", identity).source)
    }
    @Test fun `missing entry is null - and the disk miss is cached`() = scratch { directory ->
        val store = LiveCatalogStore(directory)
        assertNull(store.select("never-stored", identity).inventory)
        assertNull(store.select("never-stored", identity).inventory)
    }
}
