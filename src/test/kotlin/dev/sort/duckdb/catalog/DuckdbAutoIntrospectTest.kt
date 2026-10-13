package dev.sort.duckdb.catalog

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.dialects.postgres.model.PgDatabase
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.sql.psi.SqlResolveResult
import com.intellij.openapi.components.service
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.ui.EditorNotificationProvider
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectEngine
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.catalog.pathResolver
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.HeldIntrospections
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class DuckdbAutoIntrospectTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var introspections: HeldIntrospections
    private lateinit var requests: DuckdbAutoIntrospectService
    private lateinit var banner: DuckdbIntrospectBannerProvider
    private lateinit var source: LocalDataSource
    private lateinit var jdbc: Connection
    private lateinit var directory: Path
    private lateinit var root: PgRoot

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        introspections = HeldIntrospections(testRootDisposable)
        assertSame(DuckdbCatalogProfile, project.service<DuckdbAutoIntrospectService>().profile)
        requests = DuckdbAutoIntrospectService(project, errors.newScope(testRootDisposable))
        project.registerOrReplaceServiceInstance(DuckdbAutoIntrospectService::class.java, requests, testRootDisposable)
        banner = EditorNotificationProvider.EP_NAME.getExtensions(project).filterIsInstance<DuckdbIntrospectBannerProvider>().single()
        directory = Files.createTempDirectory(Files.createDirectories(Path.of("build", "introspect-tests")), "fixture-")
    }

    override fun tearDown() = RunAll(
        { introspections.finishAll() },
        { errors.awaitScopes() },
        { errors.assertNone() },
        { if (::source.isInitialized) LocalDataSourceManager.getInstance(project).removeDataSource(source) },
        { if (::jdbc.isInitialized) jdbc.close() },
        { DbPsiFacade.getInstance(project).clearCaches() },
        { if (::directory.isInitialized) directory.toFile().deleteRecursively() },
        { super.tearDown() },
    ).run()

    fun testFileDatabaseLoadsOnlyItsMainSchema() {
        fixture(directory.resolve("file_database.duckdb").toAbsolutePath().toString())
        assertEquals("file_database", jdbc.catalog)
        completeSchema(jdbc.catalog, "main")
    }

    fun testMemoryDatabaseLoadsOnlyItsMainSchema() {
        fixture(":memory:")
        assertEquals("memory", jdbc.catalog)
        completeSchema("memory", "main")
    }

    fun testAttachedDatabaseQualifiedTableLoadsExactlyItsNamespace() {
        fixture(":memory:", "archive")
        completeSchema("archive", "analytics")
        assertTrue(database("memory").schemas.createOrGet("main").tables.isEmpty())
        assertTrue(database("archive").schemas.createOrGet("main").tables.isEmpty())
        configure("SELECT * FROM archive.analytics.events.<caret>")
        complete()
        introspections.assertNone()
        assertNoBanner()
    }

    fun testQuotedAttachedDatabaseAndSchemaUseTheirModelNames() {
        fixture(":memory:", "arch\"ive.sales")
        completeSchema("arch\"ive.sales", "analytics")
    }

    fun testFailedAttachedNamespaceCanRetryAndOnlyActiveRequestsShowABanner() {
        fixture(":memory:", "archive")
        configure("SELECT * FROM archive.analytics.ev<caret>")
        complete()
        val failed = introspections.next()
        assertBanner("archive.analytics")
        failed.finish { throw java.sql.SQLException("offline attached database") }
        awaitIdle()
        assertInstanceOf(state(listOf("archive", "analytics")), AutoIntrospectEngine.State.Failed::class.java)
        complete()
        introspections.assertNone()
        assertNoBanner()
        PlatformTestUtil.waitWithEventsDispatching("the failed DuckDB namespace becomes retryable", {
            requests.requestPath(myFixture.file, listOf("archive", "analytics"))
        }, 70)
        val retry = introspections.next()
        assertBanner("archive.analytics")
        retry.finish { loadTables("archive", "analytics") }
        awaitIdle()
        assertEquals(AutoIntrospectEngine.State.Done, state(listOf("archive", "analytics")))
    }

    fun testAttachAndDetachRefreshTheCatalogListAndCompletionCannotResurrectADetachedDatabase() {
        fixture(":memory:")
        val scope = source.introspectionScope
        RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            val observer = DuckdbExecutionObserver(project, errors.newScope(testRootDisposable))
            fun execute(sql: String) {
                jdbc.createStatement().use { it.execute(sql) }
                val query = object : DataRequest.QueryRequest(recording.console, sql, DataRequest.newConstraints(), null) {}
                observer.requestFinished(DataRequest.Context(recording.console.session.messageBus.dataProducer,
                    DataRequest.newOutputHandler(), query, DataRequest.AUTO_COMMIT))
            }
            execute("ATTACH '${directory.resolve("attached.duckdb").toAbsolutePath()}' AS archive")
            val attach = introspections.next()
            assertSame(source, attach.context.dataSource)
            assertEquals(1, attach.context.countTasks())
            assertNull(attach.context.tasks.single().databaseName)
            assertNoBanner()
            attach.finish { loadNamespaces() }
            awaitIdle()
            assertNotNull(DuckdbCatalogProfile.pathResolver.resolve(source.model.modelRoots.toList(), listOf("archive", "main")))
            assertSame(scope, source.introspectionScope)
            execute("DETACH archive")
            val detach = introspections.next()
            assertNull(detach.context.tasks.single().databaseName)
            assertNoBanner()
            detach.finish { root.databases.remove { it.name == "archive" } }
            awaitIdle()
            assertSame(scope, source.introspectionScope)
            configure("SELECT * FROM archive.main.ev<caret>")
            complete()
            introspections.assertNone()
            assertNoBanner()
            assertFalse(requests.requestPath(myFixture.file, listOf("archive", "main")))
            assertNull(DuckdbCatalogProfile.pathResolver.resolve(source.model.modelRoots.toList(), listOf("archive")))
            assertNull(state(emptyList()))
        }
    }

    fun testForeignConsoleDoesNotIntrospectDuckdbNamespaces() {
        fixture(":memory:", "archive")
        configure("SELECT * FROM archive.analytics.ev<caret>")
        RecordingConsole(project, myFixture.file.virtualFile, "postgresql").use {
            complete()
            introspections.assertNone()
            assertNoBanner()
        }
    }

    fun testUserRoutineNamedLikeABuiltinSurvivesMetadataDeduplication() {
        fixture(":memory:")
        val schema = database("memory").schemas.createOrGet("main")
        jdbc.createStatement().use { it.execute("CREATE MACRO abs(x) AS x") }
        schema.routines.createOrGet("abs")
        source.setIntrospectionScope(CatalogScopes.select(listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA), listOf("memory", "main")))
        DbPsiFacade.getInstance(project).clearCaches()
        configure("SELECT ab<caret>")
        RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("memory", ObjectKind.DATABASE).append("main", ObjectKind.SCHEMA)))
            val items = myFixture.completeBasic().orEmpty()
            assertTrue(items.toList().toString(), items.any {
                val routine = (it.`object` as? SqlResolveResult)?.targetSymbol?.dasObject
                routine?.name == "abs" && routine.dasParent?.name == "main"
            })
            assertEquals(1, items.count { (it.`object` as? FunctionPresentation)?.name == "abs" })
        }
    }

    private fun fixture(path: String, attached: String? = null) {
        val url = "jdbc:duckdb:$path"
        jdbc = DriverManager.getConnection(url)
        jdbc.createStatement().use {
            it.execute("CREATE TABLE events(id INTEGER)")
            if (attached != null) {
                it.execute("ATTACH '${directory.resolve("attached.duckdb").toAbsolutePath()}' AS ${quote(attached)}")
                it.execute("CREATE SCHEMA ${quote(attached)}.analytics")
                it.execute("CREATE TABLE ${quote(attached)}.analytics.events(id INTEGER)")
            }
        }
        val model = ModelFactory.BLACK_HOLE.createModel(DuckdbDbms.DUCKDB_BRIKK)
        root = model.root as PgRoot
        source = LocalDataSource().apply {
            name = "DuckDB introspection fixture"
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native"))
            this.url = url
            isAutoSynchronize = false
            isKeepAlive = false
            this.model = model
        }
        loadNamespaces()
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        configure("SELECT 1<caret>")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertSame(source, DuckdbCatalogProfile.dataSources.resolve(myFixture.file).dataSource)
        assertNoBanner()
    }

    private fun loadNamespaces() {
        jdbc.metaData.catalogs.use { catalogs -> while (catalogs.next()) database(catalogs.getString("TABLE_CAT")) }
        jdbc.metaData.schemas.use { schemas ->
            while (schemas.next()) database(schemas.getString("TABLE_CATALOG")).schemas.createOrGet(schemas.getString("TABLE_SCHEM"))
        }
    }

    private fun loadTables(catalog: String, schema: String) {
        jdbc.metaData.getTables(catalog, schema, "%", arrayOf("TABLE")).use { tables ->
            while (tables.next()) database(catalog).schemas.createOrGet(schema).tables.createOrGet(tables.getString("TABLE_NAME"))
        }
    }

    private fun completeSchema(catalog: String, schema: String) {
        configure("SELECT * FROM ${quote(catalog)}.${quote(schema)}.ev<caret>")
        complete()
        val refresh = introspections.next()
        assertSame(source, refresh.context.dataSource)
        assertEquals(1, refresh.context.countTasks())
        assertEquals(catalog, refresh.context.tasks.single().databaseName)
        assertBanner("$catalog.$schema")
        val selectedCatalogs = source.introspectionScope.root.getGroup(ObjectKind.DATABASE)!!.children!!
        assertEquals(listOf(catalog), selectedCatalogs.flatMap { it.naming.names.map { name -> name.name } })
        val selectedSchemas = selectedCatalogs.single().getGroup(ObjectKind.SCHEMA)!!.children!!
        assertEquals(listOf(schema), selectedSchemas.flatMap { it.naming.names.map { name -> name.name } })
        assertNull(selectedSchemas.single().getGroup(ObjectKind.TABLE))
        refresh.finish { loadTables(catalog, schema) }
        awaitIdle()
        assertEquals(AutoIntrospectEngine.State.Done, state(listOf(catalog, schema)))
        assertTrue(database(catalog).schemas.createOrGet(schema).tables.any { it.name == "events" })
        complete()
        introspections.assertNone()
        assertNoBanner()
    }

    private fun database(name: String): PgDatabase = root.databases.createOrGet(name).apply { this.name = name }
    private fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""
    private fun state(path: List<String>) = requests.engine.state(AutoIntrospectEngine.Key(source.uniqueId, path))

    private fun configure(sql: String) {
        setSqlDialectMapping(project, null, DuckdbSqlDialect, testRootDisposable)
        myFixture.configureByText("introspect.sql", sql)
        setSqlDialectMapping(project, myFixture.file.virtualFile, DuckdbSqlDialect, testRootDisposable)
    }

    private fun complete() {
        LookupManager.getInstance(project).hideActiveLookup()
        myFixture.completeBasic()
        LookupManager.getInstance(project).hideActiveLookup()
    }

    private fun awaitIdle() {
        PlatformTestUtil.waitWithEventsDispatching("DuckDB introspection finishes", {
            !DataSourceSyncManager.getInstance().isActive(source) && requests.pending(myFixture.file.virtualFile) == null
        }, 10)
        errors.awaitScopes()
        assertNoBanner()
    }

    private fun assertNoBanner() {
        assertNull(requests.pending(myFixture.file.virtualFile))
        assertNull(banner.collectNotificationData(project, myFixture.file.virtualFile))
    }

    private fun assertBanner(namespace: String) {
        assertEquals(namespace, requests.pending(myFixture.file.virtualFile))
        assertNotNull(banner.collectNotificationData(project, myFixture.file.virtualFile))
    }
}
