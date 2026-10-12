package dev.sort.duckdb.catalog

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.Version
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.completableFunctions
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.setSqlDialectMapping
import dev.sort.duckdb.sql.DuckdbCompletionProfile
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import icons.DatabaseIcons

/**
 * Pins the Stage-4b resolution rules end to end: live REPLACES bundled for the editor's data
 * source (never a merge), bundled serves when nothing is resolvable, and the extension-offer
 * layer adds only names the active catalog lacks (case-folded match, `requires <ext>` type text).
 */
class DuckdbCatalogResolverTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private var autocomplete = false

    override fun setUp() {
        super.setUp()
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        tempDir = Files.createTempDirectory("duckdb-live-catalog-fixture")
        DuckdbLiveCatalog.baseDirOverride = tempDir
        DuckdbLiveCatalog.clearForTests()
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
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

    fun testExtensionOffersShowRequiresSpatial() {
        setSqlDialectMapping(project, null, DuckdbSqlDialect.INSTANCE)
        myFixture.configureByText("e1.sql", "SELECT * FROM st_re<caret>;")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings.orEmpty()
        assertTrue("extension offers absent: $offered", offered.containsAll(listOf("ST_Read", "ST_ReadOSM")))
        val stRead = requireNotNull(myFixture.lookupElements).first { it.lookupString == "ST_Read" }
        val presentation = LookupElementPresentation()
        stRead.renderElement(presentation)
        assertEquals("requires spatial", presentation.typeText)

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

    fun testLiveInventoryKeepsGrammarFormsAndTheirInvocationSyntax() {
        val source = LocalDataSource.create("grammar-duck", "org.duckdb.DuckDBDriver", "jdbc:duckdb:", "")
        source.databaseDriver = DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native")
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(source)
        try {
            DuckdbLiveCatalog.store(source.uniqueId, DuckdbLiveCatalog.Entry("v1.5.5", emptyList(), listOf(
                DuckdbLiveCatalog.Fn("only_live_fn", DuckdbLiveCatalog.Kind.SCALAR),
                DuckdbLiveCatalog.Fn("current_date", DuckdbLiveCatalog.Kind.SCALAR),
            ), setOf("SELECT"), 1L))
            setSqlDialectMapping(project, null, DuckdbSqlDialect.INSTANCE)
            val file = myFixture.configureByText("grammar.sql", "SELECT ca<caret>")
            val active = DuckdbCatalogResolver.activeCatalogFor(file)
            assertFalse(active.functions.any { it.name == "read_parquet" })
            assertTrue(active.functions.any { it.name == "ST_Read" && it.extension == "spatial" })
            assertTrue(active.functions.map { it.name.uppercase() }.containsAll(listOf("CAST", "TRY_CAST", "COALESCE", "CURRENT_TIMESTAMP")))
            assertEquals(setOf(InvocationSyntax.BARE, InvocationSyntax.CALL),
                active.functions.single { it.name == "current_date" }.syntaxes)
            val item = myFixture.completeBasic().orEmpty().single {
                it.lookupString == "CAST" && it.`object` is FunctionPresentation
            }
            myFixture.lookup.currentItem = item
            myFixture.finishLookup('\n')
            myFixture.checkResult("SELECT CAST<caret>")
            myFixture.configureByText("bare.sql", "SELECT current_da<caret>")
            val bare = myFixture.completeBasic().orEmpty().single {
                it.lookupString == "current_date" && it.`object` is FunctionPresentation
            }
            myFixture.lookup.currentItem = bare
            myFixture.finishLookup('\n')
            myFixture.checkResult("SELECT current_date<caret>")
        } finally {
            manager.removeDataSource(source)
        }
    }

    private fun withDataSource(block: (LocalDataSource) -> Unit) {
        val source = LocalDataSource.create("review-duck", "org.duckdb.DuckDBDriver", "jdbc:duckdb:", "")
        source.databaseDriver = DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native")
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(source)
        try {
            setSqlDialectMapping(project, null, DuckdbSqlDialect.INSTANCE)
            block(source)
        } finally {
            manager.removeDataSource(source)
        }
    }

    fun testRealHarvestKeepsCallableKindsAfterDroppingPragmas() = withDataSource { source ->
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val entry = requireNotNull(DuckdbCatalogHarvester.harvest(JdbcRowSource(connection)))
            DuckdbLiveCatalog.store(source.uniqueId, entry)
            DuckdbLiveCatalog.clearForTests()
            for ((name, kind) in listOf(
                "version" to FunctionKind.SCALAR,
                "enable_profiling" to FunctionKind.TABLE_VALUED,
                "disable_profiling" to FunctionKind.TABLE_VALUED,
                "force_checkpoint" to FunctionKind.TABLE_VALUED,
                "json_execute_serialized_sql" to FunctionKind.TABLE_VALUED,
            )) {
                assertTrue(entry.functions.any { it.name == name && it.kind == DuckdbLiveCatalog.Kind.PRAGMA })
                myFixture.configureByText("harvest.sql", "SELECT ${name.take(4)}<caret>")
                val entries = myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
                assertTrue("$name / $kind missing: $entries", entries.any { it.name == name && it.kind == kind })
            }
        }
    }

    fun testLiveInventoryDoesNotRestoreRegisteredGrammarNames() = withDataSource { source ->
        DuckdbLiveCatalog.store(source.uniqueId, DuckdbLiveCatalog.Entry("v1.5.5", emptyList(),
            listOf(DuckdbLiveCatalog.Fn("only_live_fn", DuckdbLiveCatalog.Kind.SCALAR)), setOf("SELECT"), 1L))
        val file = myFixture.configureByText("missing.sql", "SELECT current_da<caret>")
        assertFalse(DuckdbCatalogResolver.activeCatalogFor(file).functions.any { it.name.equals("current_date", true) })
        val entries = myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
        assertFalse(entries.any { it.name.equals("current_date", true) })
    }

    fun testLiveMergedListsAreReusedUntilTheHarvestEntryChanges() = withDataSource { source ->
        val first = DuckdbLiveCatalog.Entry("v1.5.5", emptyList(),
            listOf(DuckdbLiveCatalog.Fn("first_live_fn", DuckdbLiveCatalog.Kind.SCALAR)), setOf("SELECT"), 1L)
        DuckdbLiveCatalog.store(source.uniqueId, first)
        val file = myFixture.configureByText("cache.sql", "SELECT 1")
        val active = DuckdbCatalogResolver.activeCatalogFor(file)
        assertSame(active.functions, DuckdbCatalogResolver.activeCatalogFor(file).functions)
        assertSame(active.functions, DuckdbCompletionProfile.functions(file))
        assertSame(active.keywords, DuckdbCatalogResolver.activeCatalogFor(file).keywords)
        val replacement = DuckdbLiveCatalog.Entry("v1.5.5", emptyList(),
            listOf(DuckdbLiveCatalog.Fn("second_live_fn", DuckdbLiveCatalog.Kind.SCALAR)), setOf("SELECT"), 2L)
        DuckdbLiveCatalog.store(source.uniqueId, replacement)
        val refreshed = DuckdbCatalogResolver.activeCatalogFor(file)
        assertNotSame(active.functions, refreshed.functions)
        assertTrue(refreshed.functions.any { it.name == "second_live_fn" })
        assertFalse(refreshed.functions.any { it.name == "first_live_fn" })
        assertSame(refreshed.functions, DuckdbCatalogResolver.activeCatalogFor(file).functions)
    }

    fun testLiveMacroKindsUseDuckdbLookupStyles() = withDataSource { source ->
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE MACRO review_scalar(x) AS x + 1")
                it.execute("CREATE MACRO review_table(x) AS TABLE SELECT x AS value")
            }
            DuckdbLiveCatalog.store(source.uniqueId,
                requireNotNull(DuckdbCatalogHarvester.harvest(JdbcRowSource(connection))))
            myFixture.configureByText("macros.sql", "SELECT review_<caret>")
            val items = myFixture.completeBasic().orEmpty()
            val scalar = items.single { it.lookupString == "review_scalar" && it.`object` is FunctionPresentation }
            val table = items.single { it.lookupString == "review_table" && it.`object` is FunctionPresentation }
            val scalarPresentation = LookupElementPresentation.renderElement(scalar)
            assertEquals("macro", scalarPresentation.typeText)
            assertEquals(DatabaseIcons.Routine, scalarPresentation.icon)
            assertEquals("table_macro", LookupElementPresentation.renderElement(table).typeText)
        }
    }

    fun testHarvestedWindowKindReachesCompletion() = withDataSource { source ->
        val rows = CatalogRowSource { sql, row ->
            val values = when (sql) {
                DuckdbCatalogHarvester.SQL_VERSION -> listOf("v2.0.0")
                DuckdbCatalogHarvester.SQL_FUNCTIONS -> listOf("review_window", "window")
                DuckdbCatalogHarvester.SQL_KEYWORDS -> listOf("select")
                else -> emptyList()
            }
            if (values.isNotEmpty()) row { values.getOrNull(it - 1) }
        }
        DuckdbLiveCatalog.store(source.uniqueId, requireNotNull(DuckdbCatalogHarvester.harvest(rows)))
        myFixture.configureByText("window.sql", "SELECT review_<caret>")
        val item = myFixture.completeBasic().orEmpty().single {
            it.lookupString == "review_window" && it.`object` is FunctionPresentation
        }
        assertEquals(FunctionKind.WINDOW, (item.`object` as FunctionPresentation).kind)
    }

}
