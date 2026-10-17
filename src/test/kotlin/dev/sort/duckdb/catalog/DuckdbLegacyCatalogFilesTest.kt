package dev.sort.duckdb.catalog

import dev.brikk.house.sql.intellij.catalog.CatalogSource
import dev.brikk.house.sql.intellij.catalog.ConnectionIdentity
import dev.brikk.house.sql.intellij.catalog.LiveCatalogStore
import dev.brikk.house.sql.intellij.catalog.SessionFacts
import dev.brikk.house.sql.intellij.testing.JdbcCatalogRows
import dev.brikk.house.sql.metadata.FunctionKind
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class DuckdbLegacyCatalogFilesTest {
    private lateinit var directory: Path
    private lateinit var legacy: DuckdbLegacyCatalogFiles
    private val identity = ConnectionIdentity.of(listOf("DUCKDB_BRIKK", "jdbc:duckdb:", ""))

    @Before
    fun setUp() {
        directory = Files.createTempDirectory(Files.createDirectories(Path.of("build", "catalog-tests")), "legacy-")
        legacy = DuckdbLegacyCatalogFiles(directory.resolve("legacy"))
        Files.createDirectories(directory.resolve("legacy"))
    }

    @After
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    private fun write(version: Int = 1, id: String = "old-ds", records: String = "fn\tabs\tSCALAR\nfn\tversion\tPRAGMA\nkw\tSELECT") =
        legacy.file(id).also { file ->
            Files.writeString(file, "# duckdb-brikk live catalog v$version (see DuckdbLiveCatalog for the format)\nmeta\tv1.5.5\tjson\t1\n$records\n")
        }

    @Test
    fun `v1 file migrates on first selection and is deleted`() {
        val file = write()
        val store = LiveCatalogStore(directory.resolve("current"), legacy)
        val selection = store.select("old-ds", identity)
        assertTrue(selection.source is CatalogSource.Disk)
        assertEquals(listOf("abs"), selection.inventory!!.functions.map { it.def.name })
        assertFalse(selection.inventory!!.signatures)
        assertFalse(Files.exists(file))
        assertEquals(selection.inventory, LiveCatalogStore(directory.resolve("current"), legacy).select("old-ds", identity).inventory)
    }

    @Test
    fun `v2 keeps distinct callable kinds and macro kinds`() {
        val file = write(2, records = "fn\tread_csv\tSCALAR\nfn\tread_csv\tTABLE\nfn\trow_number\tWINDOW\nfn\tlist_append\tMACRO\nkw\tSELECT")
        val inventory = requireNotNull(legacy.read(file))
        assertEquals(setOf(FunctionKind.SCALAR, FunctionKind.TABLE_VALUED), inventory.functions.filter { it.def.name == "read_csv" }.map { it.def.kind }.toSet())
        assertEquals("macro", inventory.functions.single { it.def.name == "list_append" }.def.nativeKind)
    }

    @Test
    fun `filename hostile data source identities are sanitized`() {
        assertEquals("file___ds_1_weird_id.tsv", legacy.file("file://ds/1 weird#id").fileName.toString())
    }

    @Test
    fun `legacy macros with unprovable session ownership are not migrated`() {
        val file = write(2, records = "fn\tabs\tSCALAR\nfn\tunknown_macro\tMACRO\nfn\tunknown_table_macro\tTABLE_MACRO\nkw\tSELECT")
        assertEquals(listOf("abs"), requireNotNull(legacy.read(file)).functions.map { it.def.name })
    }

    @Test
    fun `corrupt or incomplete files fall back to bundled`() {
        for (records in listOf("garbage", "fn\tabs", "fn\tabs\tUNKNOWN\nkw\tSELECT", "fn\tabs\tSCALAR")) {
            write(records = records)
            assertEquals(CatalogSource.Bundled, LiveCatalogStore(directory.resolve("current"), legacy).select("old-ds", identity).source)
        }
    }

    @Test
    fun `real harvest persists signatures but never session facts`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { it.execute("CREATE TEMP MACRO ephemeral(x) AS x") }
            val harvest = requireNotNull(DuckdbCatalogHarvester.harvest(JdbcCatalogRows(connection)))
            val store = LiveCatalogStore(directory.resolve("current"))
            val session = Any()
            store.store("ds", identity, harvest.shared)
            store.storeSession(session, "ds", identity, harvest.session)
            assertTrue(store.select("ds", identity, session).session.functions.any { it.def.name == "ephemeral" })
            val cold = LiveCatalogStore(directory.resolve("current")).select("ds", identity, session)
            assertEquals(harvest.shared, cold.inventory)
            assertEquals(SessionFacts.NONE, cold.session)
            assertFalse(cold.inventory!!.functions.any { it.def.name == "ephemeral" })
        }
    }

    @Test
    fun `changed identity ignores old cache and removal deletes both formats`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val shared = requireNotNull(DuckdbCatalogHarvester.harvest(JdbcCatalogRows(connection))).shared
            val store = LiveCatalogStore(directory.resolve("current"), legacy)
            store.store("old-ds", identity, shared)
            assertEquals(CatalogSource.Bundled, store.select("old-ds", ConnectionIdentity.of(listOf("another-url"))).source)
            write()
            store.forget("old-ds")
            assertFalse(Files.exists(legacy.file("old-ds")))
            assertEquals(CatalogSource.Bundled, LiveCatalogStore(directory.resolve("current"), legacy).select("old-ds", identity).source)
        }
    }
}
