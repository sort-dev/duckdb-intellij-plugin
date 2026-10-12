package dev.sort.duckdb.catalog

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.Version
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.completableFunctions
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.setSqlDialectMapping
import dev.sort.duckdb.sql.DuckdbCompletionProfile
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the Stage-4b resolution rules end to end: live REPLACES bundled for the editor's data
 * source (never a merge), bundled serves when nothing is resolvable, and the extension-offer
 * layer adds only names the active catalog lacks (case-folded match, `requires <ext>` type text).
 */
class DuckdbCatalogResolverTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("duckdb-live-catalog-fixture")
        DuckdbLiveCatalog.baseDirOverride = tempDir
        DuckdbLiveCatalog.clearForTests()
    }

    override fun tearDown() {
        try {
            DuckdbLiveCatalog.baseDirOverride = null
            DuckdbLiveCatalog.clearForTests()
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testResolverFallsBackToBundledWithoutDataSource() {
        val file = myFixture.configureByText("nods.sql", "SELECT 1;")
        val active = DuckdbCatalogResolver.activeCatalogFor(file)
        assertFalse(active.live)
        assertNull(active.dataSourceId)
        assertSame(DUCKDB_METADATA.default, active.snapshot)
        assertSame(DUCKDB_METADATA.default.completableFunctions(), active.functions)
        assertEquals(DUCKDB_METADATA.default.keywords!!.keywords.map { it.word }.toSet(), active.keywords)
    }

    fun testLiveCatalogReplacesBundledForTheProjectsSingleDataSource() {
        // A real LocalDataSource wearing our driver (forced-dbms DUCKDB_BRIKK from driversConfig).
        val driver = DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native")
        assertNotNull("driversConfig must register duckdb-brikk-native", driver)
        val dataSource = LocalDataSource.create("live-duck", "org.duckdb.DuckDBDriver", "jdbc:duckdb:", "")
        dataSource.databaseDriver = driver
        assertEquals(DuckdbDbms.DUCKDB_BRIKK, dataSource.dbms)
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(dataSource)
        try {
            DuckdbLiveCatalog.store(
                dataSource.uniqueId,
                DuckdbLiveCatalog.Entry(
                    engineVersion = "v1.3.0",
                    loadedExtensions = listOf("json"),
                    functions = listOf(
                        DuckdbLiveCatalog.Fn("only_live_fn", DuckdbLiveCatalog.Kind.SCALAR),
                        DuckdbLiveCatalog.Fn("list_transform", DuckdbLiveCatalog.Kind.SCALAR),
                    ),
                    keywords = setOf("SELECT"),
                    harvestedAtMillis = 1L,
                ),
            )

            // Resolver level: live wins wholesale…
            val file = myFixture.configureByText("live.sql", "SELECT 1;")
            val active = DuckdbCatalogResolver.activeCatalogFor(file)
            assertTrue(active.live)
            assertEquals(dataSource.uniqueId, active.dataSourceId)
            assertTrue(active.functions.any { it.name == "only_live_fn" })
            // …and REPLACES, never merges: a bundled-only name must not leak into a live catalog
            // (a connected 1.3 engine must not be offered 1.5.5-only functions).
            assertFalse(active.functions.any { it.name == "read_parquet" })
            assertEquals(setOf("SELECT"), active.keywords)

            // Completion level, same two assertions through the real contributor.
            setSqlDialectMapping(project, null, DuckdbSqlDialect.INSTANCE)
            myFixture.configureByText("c1.sql", "SELECT only_liv<caret> FROM t;")
            myFixture.completeBasic()
            val offered = myFixture.lookupElementStrings.orEmpty()
            val text = myFixture.editor.document.text
            assertTrue(
                "live-only function must be offered; lookups=$offered text=$text",
                offered.contains("only_live_fn") || text.contains("only_live_fn("),
            )
            myFixture.configureByText("c2.sql", "SELECT * FROM read_par<caret>;")
            myFixture.completeBasic()
            assertFalse(myFixture.lookupElementStrings.orEmpty().contains("read_parquet"))
            assertFalse(myFixture.editor.document.text.contains("read_parquet("))
        } finally {
            manager.removeDataSource(dataSource)
        }
    }

    fun testExtensionOffersPreserveNamesAndFoldCaseForSuppression() {
        setSqlDialectMapping(project, null, DuckdbSqlDialect.INSTANCE)
        myFixture.configureByText("e1.sql", "SELECT * FROM st_re<caret>;")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue("extension offers absent: $offered", offered.containsAll(listOf("ST_Read", "ST_ReadOSM")))
        val stRead = requireNotNull(myFixture.lookupElements).first { it.lookupString == "ST_Read" }
        val presentation = LookupElementPresentation()
        stRead.renderElement(presentation)
        assertEquals("requires spatial", presentation.typeText)

        // Suppression: the bundled catalog already has list_transform, so the offer adds nothing.
        myFixture.configureByText("e2.sql", "SELECT list_tr<caret> FROM t;")
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings.orEmpty()
        val inserted = myFixture.editor.document.text.contains("list_transform(")
        if (!inserted) {
            assertEquals("exactly one list_transform: $strings", 1, strings.count { it.equals("list_transform", true) })
        }
        assertFalse("mixed-case duplicate must not be offered", strings.contains("List_Transform"))
    }

    fun testOfflineSnapshotUsesTheKnownServerVersion() {
        val source = LocalDataSource.create("versioned-duck", "org.duckdb.DuckDBDriver", "jdbc:duckdb:", "")
        source.databaseDriver = DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native")
        source.info.setDatabaseVersion(Version.of("2.0.0"))
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(source)
        try {
            val file = myFixture.configureByText("version.sql", "SELECT 1")
            val active = DuckdbCatalogResolver.activeCatalogFor(file)
            assertFalse(active.live)
            assertSame(DUCKDB_METADATA.forServerVersion(source.databaseVersion.version).snapshot, active.snapshot)
            assertSame(active.snapshot, DuckdbCompletionProfile.snapshot(file))
            assertEquals(active.functions, DuckdbCompletionProfile.functions(file))
        } finally {
            manager.removeDataSource(source)
        }
    }

    fun testLiveFunctionsSuppressExtensionOffersIgnoringCase() {
        val source = LocalDataSource.create("spatial-duck", "org.duckdb.DuckDBDriver", "jdbc:duckdb:", "")
        source.databaseDriver = DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native")
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(source)
        try {
            DuckdbLiveCatalog.store(source.uniqueId, DuckdbLiveCatalog.Entry("v1.5.5", listOf("spatial"), listOf(
                DuckdbLiveCatalog.Fn("st_area", DuckdbLiveCatalog.Kind.SCALAR),
                DuckdbLiveCatalog.Fn("session_macro", DuckdbLiveCatalog.Kind.MACRO),
                DuckdbLiveCatalog.Fn("show_tables", DuckdbLiveCatalog.Kind.PRAGMA),
            ), setOf("SELECT"), 1L))
            setSqlDialectMapping(project, null, DuckdbSqlDialect.INSTANCE)
            val file = myFixture.configureByText("spatial.sql", "SELECT st_ar<caret>")
            val active = DuckdbCatalogResolver.activeCatalogFor(file)
            assertEquals("macro", active.functions.single { it.name == "session_macro" }.nativeKind)
            assertFalse(active.functions.any { it.name == "show_tables" })
            assertSame(DUCKDB_METADATA.forServerVersion("v1.5.5").snapshot, active.snapshot)
            val entries = myFixture.completeBasic().orEmpty().mapNotNull {
                it.`object` as? FunctionPresentation
            }.filter { it.name.equals("st_area", true) }
            assertEquals(1, entries.size)
            assertEquals("st_area", entries.single().name)
            assertNull(entries.single().extension)
        } finally {
            manager.removeDataSource(source)
        }
    }

}
