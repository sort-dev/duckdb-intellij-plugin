package dev.sort.duckdb.catalog

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.sql.DuckdbFunctionCatalog
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
            DuckdbExtensionOffers.setOffersForTests(null)
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
        // Same instances, not copies: the bundled snapshot IS the fallback catalog.
        assertSame(DuckdbFunctionCatalog.functions, active.functions)
        assertSame(DuckdbFunctionCatalog.keywords, active.keywords)
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
                        DuckdbFunctionCatalog.Fn("only_live_fn", DuckdbFunctionCatalog.Kind.SCALAR),
                        DuckdbFunctionCatalog.Fn("list_transform", DuckdbFunctionCatalog.Kind.SCALAR),
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
            SqlDialectMappings.getInstance(project).setMapping(null, DuckdbSqlDialect.INSTANCE)
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

    fun testExtensionOffersCarryRequiresTagAndFoldCaseForSuppression() {
        DuckdbExtensionOffers.setOffersForTests(
            listOf(
                // Mixed case exactly as duckdb_functions() reports extension names (contract).
                DuckdbExtensionOffers.Offer("ST_Read", DuckdbFunctionCatalog.Kind.TABLE, "spatial"),
                DuckdbExtensionOffers.Offer("ST_ReadOSM", DuckdbFunctionCatalog.Kind.TABLE, "spatial"),
                // Case-variant of a bundled name: must be suppressed by the case-folded match.
                DuckdbExtensionOffers.Offer("List_Transform", DuckdbFunctionCatalog.Kind.SCALAR, "spatial"),
            ),
        )
        SqlDialectMappings.getInstance(project).setMapping(null, DuckdbSqlDialect.INSTANCE)
        myFixture.configureByText("e1.sql", "SELECT * FROM st_re<caret>;")
        myFixture.completeBasic()
        val elements = myFixture.lookupElements
        assertNotNull("two ST_ candidates must keep the lookup open", elements)
        val stRead = elements!!.first { it.lookupString == "ST_Read" } // original case preserved
        val presentation = LookupElementPresentation()
        stRead.renderElement(presentation)
        assertEquals("requires spatial", presentation.typeText)

        // Suppression: the bundled catalog already has list_transform, so the offer adds nothing.
        myFixture.configureByText("e2.sql", "SELECT list_tr<caret> FROM t;")
        myFixture.completeBasic()
        val strings = myFixture.lookupElementStrings.orEmpty()
        val inserted = myFixture.editor.document.text.contains("list_transform(")
        if (!inserted) {
            assertEquals("exactly one list_transform: $strings", 1, strings.count { it.equals("list_transform", ignoreCase = true) })
        }
        assertFalse("mixed-case duplicate must not be offered", strings.contains("List_Transform"))
    }

    fun testParseToleratesCommentsAndJunkRows() {
        val text = listOf(
            "# harvested by the extension-map lane",
            "ST_Area\tscalar\tspatial",
            "create_fts_index\tpragma\tfts",
            "!~~\tscalar\tspatial", // operator name -> dropped
            "broken-line-no-tabs",
            "name\tkind-only",
            "",
        ).joinToString("\n")
        assertEquals(
            listOf(
                DuckdbExtensionOffers.Offer("ST_Area", DuckdbFunctionCatalog.Kind.SCALAR, "spatial"),
                DuckdbExtensionOffers.Offer("create_fts_index", DuckdbFunctionCatalog.Kind.PRAGMA, "fts"),
            ),
            DuckdbExtensionOffers.parse(text),
        )
    }
}
