package dev.sort.duckdb.catalog

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.name

/** Pins the one-TSV-per-data-source persistence: round-trip, corrupt-file degrade, id sanitizing. */
class DuckdbLiveCatalogPersistenceTest {

    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("duckdb-live-catalog-test")
        DuckdbLiveCatalog.baseDirOverride = tempDir
        DuckdbLiveCatalog.clearForTests()
    }

    @After
    fun tearDown() {
        DuckdbLiveCatalog.baseDirOverride = null
        DuckdbLiveCatalog.clearForTests()
        tempDir.toFile().deleteRecursively()
    }

    private fun entry() = DuckdbLiveCatalog.Entry(
        engineVersion = "v1.5.5",
        loadedExtensions = listOf("json", "parquet"),
        functions = listOf(
            DuckdbLiveCatalog.Fn("only_live_fn", DuckdbLiveCatalog.Kind.SCALAR),
            DuckdbLiveCatalog.Fn("ST_Area", DuckdbLiveCatalog.Kind.SCALAR), // mixed case must survive
            DuckdbLiveCatalog.Fn("read_csv", DuckdbLiveCatalog.Kind.TABLE),
        ),
        keywords = setOf("SELECT", "PIVOT"),
        harvestedAtMillis = 1_753_000_000_000L,
    )

    @Test
    fun `round trip - write, drop memory, load, equal`() {
        // A filename-hostile id proves the sanitizer while we are at it.
        val dsId = "file://ds/1 weird#id"
        DuckdbLiveCatalog.store(dsId, entry())
        DuckdbLiveCatalog.clearForTests() // memory gone; the file remains
        assertEquals(entry(), DuckdbLiveCatalog.entryFor(dsId))
    }

    @Test
    fun `corrupt file degrades to absent - bundled snapshot takes over`() {
        DuckdbLiveCatalog.store("corrupt-ds", entry())
        val file = Files.list(tempDir).use { s -> s.filter { it.name.endsWith(".tsv") }.findFirst().get() }
        Files.writeString(file, "meta\tonly-two-fields\ngarbage line\n")
        DuckdbLiveCatalog.clearForTests()
        assertNull(DuckdbLiveCatalog.entryFor("corrupt-ds"))
    }

    @Test
    fun `cancellation during disk reading leaves the catalog readable on retry`() {
        for (cancellation in listOf(ProcessCanceledException(), CancellationException())) {
            val expected = entry()
            DuckdbLiveCatalog.store("cancelled-ds", expected)
            DuckdbLiveCatalog.clearForTests()
            assertSame(cancellation, assertThrows(cancellation.javaClass) {
                DuckdbLiveCatalog.entryFor("cancelled-ds") { file ->
                    Files.readAllLines(file).also { throw cancellation }
                }
            })
            assertEquals(expected, DuckdbLiveCatalog.entryFor("cancelled-ds"))
        }
    }

    @Test
    fun `missing entry is null - and the disk miss is cached`() {
        assertNull(DuckdbLiveCatalog.entryFor("never-stored"))
        assertNull(DuckdbLiveCatalog.entryFor("never-stored"))
        assertNull(DuckdbLiveCatalog.entryFor(null))
    }
}
