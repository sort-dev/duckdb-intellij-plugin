package dev.sort.duckdb.catalog

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.database.util.Version
import dev.brikk.house.sql.intellij.catalog.CatalogSource
import dev.brikk.house.sql.intellij.completion.completableFunctions
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.completion.SqlLookupStyles
import dev.brikk.house.sql.intellij.completion.SqlLookupIcons
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.metadata.DUCKDB_METADATA
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.InvocationSyntax
import dev.sort.duckdb.sql.DuckdbCompletionProfile
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.sql.DriverManager
import java.sql.Connection
import java.sql.Statement
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class DuckdbCatalogResolverTest : DuckdbCatalogTestCase() {
    fun testLiveCatalogReplacesBundledForTheProjectsSingleDataSource() = testCompleteCacheReplacesBundledRegistryThroughCompletion()

    fun testExtensionOffersCarryRequiresTagAndFoldCaseForSuppression() {
        testLiveFunctionsSuppressExtensionOffersIgnoringCase()
        val names = completions("ST_Re", table = true)
        assertTrue(names.toString(), names.any { it.name == "ST_Read" && it.extension == "spatial" })
    }

    fun testParseToleratesCommentsAndJunkRows() {
        val legacy = DuckdbLegacyCatalogFiles(directory)
        val file = legacy.file("comments")
        val text = "# duckdb-brikk live catalog v1 (see DuckdbLiveCatalog for the format)\n# comment\nmeta\tv1.5.5\t\t1\nfn\tabs\tSCALAR\nkw\tSELECT\n\n"
        java.nio.file.Files.writeString(file, text)
        assertEquals(listOf("abs"), requireNotNull(legacy.read(file)).functions.map { it.def.name })
        java.nio.file.Files.writeString(file, text + "broken-line-no-tabs\n")
        // The removed extension TSV loader is replaced by typed metadata. An incomplete legacy
        // inventory now degrades as a whole instead of silently becoming a smaller live catalog.
        assertNull(legacy.read(file))
    }
    private var autocomplete = false

    override fun setUp() {
        super.setUp()
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        setSqlDialectMapping(project, null, DuckdbSqlDialect)
    }

    override fun tearDown() {
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
        super.tearDown()
    }

    private fun completions(prefix: String, table: Boolean = false): List<FunctionPresentation> {
        myFixture.configureByText("completion.sql", "${if (table) "SELECT * FROM" else "SELECT"} $prefix<caret>")
        return myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
    }

    fun testResolverFallsBackToBundledWithoutDataSource() {
        val file = myFixture.configureByText("nods.sql", "SELECT 1")
        val active = DuckdbCatalogResolver.activeCatalogFor(file)
        assertFalse(active.live)
        assertNull(active.dataSourceId)
        assertSame(DUCKDB_METADATA.default, active.snapshot)
        assertEquals(DUCKDB_METADATA.default.completableFunctions(), active.functions)
    }

    fun testCompleteCacheReplacesBundledRegistryThroughCompletion() {
        val source = source()
        legacy(source, listOf("only_live_fn" to "SCALAR"))
        assertTrue(completions("only_liv").any { it.name == "only_live_fn" })
        assertFalse(completions("read_par").any { it.name == "read_parquet" })
        assertTrue(DuckdbCatalogResolver.activeCatalogFor(myFixture.file).live)
        assertTrue(catalog.selection(source).source is CatalogSource.Disk)
    }

    fun testOfflineSnapshotUsesTheKnownServerVersion() {
        val source = source()
        source.info.setDatabaseVersion(Version.of("2.0.0"))
        val file = myFixture.configureByText("version.sql", "SELECT 1")
        val active = DuckdbCatalogResolver.activeCatalogFor(file)
        assertFalse(active.live)
        assertSame(DUCKDB_METADATA.forServerVersion(source.databaseVersion.version).snapshot, active.snapshot)
        assertSame(active.snapshot, DuckdbCompletionProfile.snapshot(file))
    }

    fun testLiveFunctionsSuppressExtensionOffersIgnoringCase() {
        legacy(source(), listOf("st_area" to "SCALAR"))
        val entries = completions("st_ar").filter { it.name.equals("st_area", true) }
        assertEquals(1, entries.size)
        assertNull(entries.single().extension)
    }

    fun testLiveInventoryKeepsGrammarFormsAndTheirInvocationSyntax() {
        legacy(source(), listOf("only_live_fn" to "SCALAR", "current_date" to "SCALAR"))
        val file = myFixture.configureByText("grammar.sql", "SELECT 1")
        val active = DuckdbCatalogResolver.activeCatalogFor(file)
        assertTrue(active.functions.any { it.name == "ST_Read" && it.extension == "spatial" })
        assertTrue(active.functions.map { it.name.uppercase() }.containsAll(listOf("CAST", "TRY_CAST", "COALESCE", "CURRENT_TIMESTAMP")))
        assertEquals(setOf(InvocationSyntax.BARE, InvocationSyntax.CALL), active.functions.single { it.name == "current_date" }.syntaxes)
    }

    fun testRealHarvestKeepsAllFiveDualPragmaFunctionNamesThroughCompletionAndDiskFallback() {
        val source = source()
        DriverManager.getConnection("jdbc:duckdb:").use { connected(it, source) }
        replaceCatalog()
        assertTrue(catalog.selection(source).source is CatalogSource.Disk)
        for ((name, kind) in listOf("version" to FunctionKind.SCALAR,
            "enable_profiling" to FunctionKind.TABLE_VALUED, "disable_profiling" to FunctionKind.TABLE_VALUED,
            "force_checkpoint" to FunctionKind.TABLE_VALUED, "json_execute_serialized_sql" to FunctionKind.TABLE_VALUED)) {
            val table = kind == FunctionKind.TABLE_VALUED
            assertTrue("$name / $kind missing", completions(name.take(4), table).any { it.name == name && it.kind == kind })
            assertFalse("$name offered in wrong position", completions(name.take(4), !table).any { it.name == name })
        }
    }

    fun testLiveInventoryDoesNotRestoreMissingRegisteredGrammarNames() {
        legacy(source(), listOf("only_live_fn" to "SCALAR"))
        assertFalse(completions("current_da").any { it.name.equals("current_date", true) })
    }

    fun testMergedFunctionListsAreReusedUntilAnotherHarvest() {
        val source = source()
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connected(connection, source)
            val file = myFixture.configureByText("cache.sql", "SELECT 1")
            val first = DuckdbCatalogResolver.activeCatalogFor(file)
            assertSame(first.functions, DuckdbCompletionProfile.functions(file))
            assertSame(first.keywordCatalog, DuckdbCompletionProfile.keywordCatalog(file, first.snapshot))
            connection.createStatement().use { it.execute("CREATE MACRO second_live_fn(x) AS x") }
            connected(connection, source)
            val second = DuckdbCatalogResolver.activeCatalogFor(file)
            assertNotSame(first.functions, second.functions)
            assertTrue(second.functions.any { it.name == "second_live_fn" && it.nativeKind == "macro" })
        }
    }

    fun testHarvestedMacroAndTableMacroKindsReachCompletion() {
        val source = source()
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE MACRO review_scalar(x) AS x + 1")
                it.execute("CREATE MACRO review_table(x) AS TABLE SELECT x AS value")
            }
            connected(connection, source)
            val functions = completions("review_")
            assertEquals("macro", functions.single { it.name == "review_scalar" }.nativeKind)
            assertFalse(functions.toString(), functions.any { it.name == "review_table" })
            val tables = completions("review_", table = true)
            assertEquals("table_macro", tables.single { it.name == "review_table" }.nativeKind)
            assertFalse(tables.toString(), tables.any { it.name == "review_scalar" })
        }
    }

    fun testWindowKindReachesCompletion() {
        legacy(source(), listOf("review_window" to "WINDOW"))
        assertEquals(FunctionKind.WINDOW, completions("review_").single { it.name == "review_window" }.kind)
        for (kind in listOf(SqlLookupKind.WINDOW, SqlLookupKind.BRIKK_COLUMN, SqlLookupKind.PIPE_STAGE)) {
            assertEquals(kind.name, SqlLookupStyles.DEFAULT[kind], DuckdbCompletionProfile.styles[kind])
        }
        assertSame(SqlLookupIcons.WindowFunction, DuckdbCompletionProfile.styles[SqlLookupKind.WINDOW].icon)
        assertSame(SqlLookupIcons.BrikkColumn, DuckdbCompletionProfile.styles[SqlLookupKind.BRIKK_COLUMN].icon)
    }

    fun testLiveKeywordsReplaceBundledKeywordsThroughCompletion() {
        legacy(source(), listOf("only_live_fn" to "SCALAR"), listOf("REVIEW_LIVE_KEYWORD"))
        myFixture.configureByText("keyword.sql", "SELECT review_liv<caret>")
        assertTrue(myFixture.completeBasic().orEmpty().any { it.lookupString == "REVIEW_LIVE_KEYWORD" && it.`object` !is FunctionPresentation })
        myFixture.configureByText("bundled.sql", "SELECT ab<caret>")
        assertFalse(myFixture.completeBasic().orEmpty().any { it.lookupString == "ABORT" })
    }

    fun testChangedUrlIgnoresTheRealHarvestCache() {
        val source = source()
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { it.execute("CREATE MACRO previous_server(x) AS x") }
            connected(connection, source)
        }
        replaceCatalog()
        assertTrue(completions("previous_ser").any { it.name == "previous_server" })
        source.url = "jdbc:duckdb:another-server"
        assertFalse(completions("previous_ser").any { it.name == "previous_server" })
        assertEquals(CatalogSource.Bundled, catalog.selection(source).source)
    }

    fun testFailedRefreshKeepsTheLastCompleteHarvest() {
        val source = source()
        val connection = DriverManager.getConnection("jdbc:duckdb:")
        connected(connection, source)
        val previous = catalog.selection(source).inventory
        connection.close()
        connected(connection, source)
        assertSame(previous, catalog.selection(source).inventory)
        replaceCatalog()
        assertEquals(previous, catalog.selection(source).inventory)
    }

    fun testDeadlineCancelsTheJdbcStatementAndKeepsTheCompleteCache() {
        val source = source()
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connected(connection, source)
            val previous = catalog.selection(source).inventory
            val cancelled = AtomicBoolean()
            val wrapped = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                val result = invokeJdbc { method.invoke(connection, *args.orEmpty()) }
                if (result !is Statement) result else Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, call, values ->
                    when (call.name) {
                        "setQueryTimeout" -> null
                        "cancel" -> { cancelled.set(true); invokeJdbc { call.invoke(result, *values.orEmpty()) } }
                        "executeQuery" -> invokeJdbc {
                            call.invoke(result, if (values?.firstOrNull() == DuckdbCatalogHarvester.SQL_VERSION)
                                "SELECT count(*) FROM range(1000000000000)" else values?.firstOrNull())
                        }
                        else -> invokeJdbc { call.invoke(result, *values.orEmpty()) }
                    }
                }
            } as Connection
            connected(wrapped, source)
            assertTrue("the deadline must cancel the running statement", cancelled.get())
            assertSame(previous, catalog.selection(source).inventory)
            replaceCatalog()
            assertEquals(previous, catalog.selection(source).inventory)
        }
    }

    private fun invokeJdbc(call: () -> Any?): Any? = try {
        call()
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
}
