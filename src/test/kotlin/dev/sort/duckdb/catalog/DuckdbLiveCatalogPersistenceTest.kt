package dev.sort.duckdb.catalog

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            DuckdbLiveCatalog.Fn("read_csv", DuckdbLiveCatalog.Kind.SCALAR),
            DuckdbLiveCatalog.Fn("row_number", DuckdbLiveCatalog.Kind.WINDOW),
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
        assertCatalogContents(entry(), DuckdbLiveCatalog.entryFor(dsId))
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
            assertCatalogContents(expected, DuckdbLiveCatalog.entryFor("cancelled-ds"))
        }
    }

    @Test
    fun `missing entry is null - and the disk miss is cached`() {
        assertNull(DuckdbLiveCatalog.entryFor("never-stored"))
        assertNull(DuckdbLiveCatalog.entryFor("never-stored"))
        assertNull(DuckdbLiveCatalog.entryFor(null))
    }

    @Test
    fun `v1 first-kind files are ignored until a complete refresh replaces them`() {
        val file = tempDir.resolve("old-ds.tsv")
        Files.writeString(file, """
            # duckdb-brikk live catalog v1 (see DuckdbLiveCatalog for the format)
            meta\tv1.5.5\tjson\t1
            fn\tversion\tPRAGMA
            kw\tSELECT
        """.trimIndent().replace("\\t", "\t"))
        assertNull(DuckdbLiveCatalog.entryFor("old-ds"))
        val complete = entry()
        DuckdbLiveCatalog.store("old-ds", complete)
        DuckdbLiveCatalog.clearForTests()
        assertCatalogContents(complete, DuckdbLiveCatalog.entryFor("old-ds"))
        assertEquals("# duckdb-brikk live catalog v2 (see DuckdbLiveCatalog for the format)", Files.readAllLines(file).first())
    }

    @Test
    fun `entry owns immutable copies of the harvested collections`() {
        val extensions = mutableListOf("json")
        val functions = mutableListOf(DuckdbLiveCatalog.Fn("version", DuckdbLiveCatalog.Kind.SCALAR))
        val keywords = mutableSetOf("SELECT")
        val entry = DuckdbLiveCatalog.Entry("v1.5.5", extensions, functions, keywords, 1L)
        extensions.clear()
        functions.clear()
        keywords.clear()
        assertEquals(listOf("json"), entry.loadedExtensions)
        assertEquals(listOf(DuckdbLiveCatalog.Fn("version", DuckdbLiveCatalog.Kind.SCALAR)), entry.functions)
        assertEquals(setOf("SELECT"), entry.keywords)
    }
}

internal fun assertCatalogContents(expected: DuckdbLiveCatalog.Entry, actual: DuckdbLiveCatalog.Entry?) {
    assertNotNull(actual)
    actual!!
    assertEquals(expected.engineVersion, actual.engineVersion)
    assertEquals(expected.loadedExtensions, actual.loadedExtensions)
    assertEquals(expected.functions, actual.functions)
    assertEquals(expected.keywords, actual.keywords)
    assertEquals(expected.harvestedAtMillis, actual.harvestedAtMillis)
}
