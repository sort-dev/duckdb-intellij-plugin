package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.util.LoaderContext
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.DataBus
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.connection.throwable.info.ErrorInfo
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.util.concurrent.CopyOnWriteArrayList
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import dev.sort.duckdb.DuckdbDbms
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/** No held executor and no handwritten model updates: the IDE's JDBC process does every sync. */
class DuckdbRealIntrospectionTest : DuckdbCatalogTestCase() {
    private lateinit var driver: DatabaseDriverImpl
    private lateinit var target: LocalDataSource
    private val consoles = mutableListOf<JdbcConsole>()
    private val sessions = mutableListOf<DatabaseSession>()
    private val finished = CopyOnWriteArrayList<DataRequest>()
    private val failures = CopyOnWriteArrayList<String>()
    private var autocomplete = false
    private data class Sync(val context: LoaderContext, val tasks: List<String>, val catalogs: List<String?>)
    private val syncs = CopyOnWriteArrayList<Sync>()

    override fun setUp() {
        super.setUp()
        autocomplete = com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        project.basePath?.let { java.nio.file.Files.createDirectories(java.nio.file.Path.of(it)) }
        val main = directory.resolve("primary.duckdb")
        for ((file, schema, unique) in listOf(Triple(main, "web", "primary_only"), Triple(directory.resolve("lake-one.duckdb"), "sales", "one_only"), Triple(directory.resolve("lake-two.duckdb"), "sales", "two_only"))) {
            DriverManager.getConnection("jdbc:duckdb:${file.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA $schema")
                    statement.execute("CREATE TABLE $schema.events(id BIGINT NOT NULL, label VARCHAR, $unique INT)")
                    statement.execute("CREATE VIEW $schema.event_view AS SELECT id, label FROM $schema.events")
                    statement.execute("CREATE MACRO $schema.plus_one(x) AS x + 1")
                    if (schema == "web") {
                        statement.execute("CREATE SCHEMA audit")
                        statement.execute("CREATE TABLE audit.events(id BIGINT NOT NULL, label VARCHAR, audit_only INT)")
                    }
                }
            }
        }
        val jar = System.getProperty("java.class.path").split(File.pathSeparator).first { "duckdb_jdbc" in it }
        driver = DatabaseDriverManager.getInstance().createDriver("introspection-${UUID.randomUUID()}", "Introspection DuckDB",
            UrlTemplate("default", "jdbc:duckdb:{file}")) as DatabaseDriverImpl
        driver.setDriverClass("org.duckdb.DuckDBDriver")
        driver.setSqlDialect("DuckDBSQL")
        driver.setForcedDbms(DuckdbDbms.DUCKDB_BRIKK)
        driver.setAdditionalClasspathElements(SimpleClasspathElementFactory.createElements("jar://$jar!/"))
        driver.setVmOptions("-Djava.io.tmpdir=${directory.toAbsolutePath()}")
        target = source().apply {
            databaseDriver = driver
            url = "jdbc:duckdb:${main.toAbsolutePath()}"
            isAutoSynchronize = false
            isKeepAlive = false
            initScript = "ATTACH IF NOT EXISTS '${directory.resolve("lake-one.duckdb").toAbsolutePath()}' AS lake_one; " +
                "ATTACH IF NOT EXISTS '${directory.resolve("lake-two.duckdb").toAbsolutePath()}' AS lake_two;"
        }
        com.intellij.database.psi.DbPsiFacade.getInstance(project).clearCaches()
        setSqlDialectMapping(project, null, DuckdbSqlDialect)
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun error(context: DataRequest.Context, error: ErrorInfo) { failures += error.toString() }
            override fun requestFinished(context: DataRequest.Context) { finished += context.request }
        }, testRootDisposable)
        val observer = DuckdbExecutionObserver(project, errors.newScope(testRootDisposable))
        Disposer.register(testRootDisposable, observer)
        observer.start()
        // Observe the actual executor and delegate to it unchanged: no held tasks or fake metadata.
        val field = DataSourceSyncManager::class.java.getDeclaredField("ourDefaultExecutor").apply { isAccessible = true }
        val actual = field.get(null) as DataSourceSyncManager.DatabaseExecutor
        DataSourceSyncManager.withDefaultExecutor(testRootDisposable) { task, path, force, consumer ->
            if (syncs.none { it.context === task.context }) syncs += Sync(task.context,
                task.context.tasks.map { "${it.javaClass.simpleName}:${it.name}" }, task.context.tasks.map { it.databaseName })
            actual.perform(task, path, force) { connection ->
                assertFalse("Metadata ran on EDT", com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread)
                assertTrue("Metadata connection must be in auto-commit", connection.autoCommit)
                consumer.consume(connection)
            }
        }
    }

    override fun tearDown() {
        try {
            LookupManager.getInstance(project).hideActiveLookup()
            FileDocumentManager.getInstance().saveAllDocuments()
            com.intellij.codeInsight.CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            consoles.forEach { DatabaseSessionViewService.getInstance().destroyView(it); Disposer.dispose(it) }
            sessions.forEach(Disposer::dispose)
            assertEmpty(failures)
        } finally {
            try {
                if (::driver.isInitialized) DatabaseDriverManager.getInstance().removeDriver(driver)
            } finally {
                super.tearDown()
            }
        }
    }

    private fun root() = (target.model as com.intellij.database.model.basic.BasicModel).root as PgRoot
    private fun rootOrNull() = (target.model as? com.intellij.database.model.basic.BasicModel)?.root as? PgRoot

    private fun namespaces() {
        introspect(LoaderContext.selectListNamespacesTask(project, target))
        for (name in listOf("primary", "lake_one", "lake_two")) {
            introspect(LoaderContext.selectTask(project, target, requireNotNull(com.intellij.database.introspection.prepareOneElementRefreshTask(target.uniqueId, root().databases.createOrGet(name)))))
        }
    }

    private inner class Console(val file: VirtualFile, val session: DatabaseSession, val console: JdbcConsole) {
        val psi: PsiFile get() = requireNotNull(PsiManager.getInstance(project).findFile(file))
        fun open(sql: String) {
            myFixture.configureFromExistingVirtualFile(file)
            val caret = sql.indexOf("<caret>").takeIf { it >= 0 }
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(sql.replace("<caret>", "")) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
            myFixture.editor.caretModel.moveToOffset(caret ?: myFixture.editor.document.textLength)
        }
        fun run(sql: String) {
            open(sql)
            FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
            val request = requireNotNull(ConsoleDataRequest.newConsoleRequest(console, myFixture.editor, console.scriptModel, false))
            session.messageBus.dataProducer.processRequest(request)
            PlatformTestUtil.waitWithEventsDispatching("real console executes $sql", { request in finished }, 30)
            assertEmpty(failures)
        }
    }

    private fun console(): Console {
        val file = myFixture.addFileToProject("real-introspection-${UUID.randomUUID()}.sql", "SELECT 1").virtualFile
        val session = DatabaseSessionManager.openSession(project, target, "Introspection")
        val console = JdbcConsole.newConsole(project).forFile(file).fromDataSource(target).useSession(session).build()
        consoles += console; sessions += session
        return Console(file, session, console)
    }

    private fun waitLoaded(catalog: String, schema: String) {
        PlatformTestUtil.waitWithEventsDispatching("$catalog.$schema tables and columns are introspected", {
            val namespace = rootOrNull()?.databases?.firstOrNull { it.name == catalog }?.schemas?.firstOrNull { it.name == schema }
            namespace?.tables?.firstOrNull { it.name == "events" }?.columns?.isNotEmpty() == true && !DataSourceSyncManager.getInstance().isActive(target)
        }, 60)
        errors.assertNone()
    }

    private fun Console.columns(sql: String): List<String> {
        open(sql)
        val names = myFixture.completeBasic().orEmpty().map { it.lookupString }
        LookupManager.getInstance(project).hideActiveLookup()
        return names
    }

    private fun Console.assertClean(sql: String) {
        open(sql)
        myFixture.enableInspections(dev.sort.duckdb.registeredSqlInspection("SqlResolveInspection"))
        assertEmpty("False errors after real introspection: $sql", myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR })
    }

    private fun clearModel() {
        PlatformTestUtil.waitWithEventsDispatching("previous sync finishes before clearing", { !DataSourceSyncManager.getInstance().isActive(target) }, 30)
        // The platform's clear operations, retaining the data source, console and plugin service.
        val cleared = AtomicReference<Result<Unit>>()
        errors.newScope(testRootDisposable).launch(kotlinx.coroutines.Dispatchers.IO) {
            cleared.set(runCatching { target.clearIntrospectionCache(); target.clearModel() })
        }
        PlatformTestUtil.waitWithEventsDispatching("platform clears introspection data off EDT", { cleared.get() != null }, 30)
        cleared.get().getOrThrow()
        com.intellij.database.psi.DbPsiFacade.getInstance(project).clearCaches()
        assertEmpty(target.model.modelRoots.toList())
        syncs.clear()
    }

    private fun Console.references(sql: String) {
        open(sql)
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val registered = com.intellij.lang.LanguageAnnotators.INSTANCE.allForLanguage(DuckdbSqlDialect)
            .single { it is DuckdbReferenceIntrospectAnnotator }
        val holder = com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl(com.intellij.lang.annotation.AnnotationSession(psi), false)
        holder.runAnnotatorWithContext(psi, com.intellij.lang.annotation.Annotator { _, annotations -> registered.annotate(psi, annotations) })
        holder.assertAllAnnotationsCreated()
    }

    private fun Console.assertBanner(active: Boolean) {
        val banner = com.intellij.ui.EditorNotificationProvider.EP_NAME.getExtensions(project).filterIsInstance<DuckdbIntrospectBannerProvider>().single()
        if (active) assertNotNull("Discovery banner missing", banner.collectNotificationData(project, file))
        else assertNull("Discovery banner did not clear", banner.collectNotificationData(project, file))
    }

    private fun dispatchBriefly() {
        val tick = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
            .schedule({}, 350, java.util.concurrent.TimeUnit.MILLISECONDS)
        PlatformTestUtil.waitWithEventsDispatching("dispatch pending requests", { tick.isDone }, 3)
        errors.assertNone()
    }

    private fun assertDiscovery(catalog: String, count: Int) {
        assertEquals(syncs.toString(), count, syncs.size)
        val rootTasks = LoaderContext.selectListNamespacesTask(project, target).tasks.map { "${it.javaClass.simpleName}:${it.name}" }
        assertEquals("The first operation must be a cheap namespace list", rootTasks, syncs.first().tasks)
        assertEquals(syncs.toString(), listOf(null), syncs.first().catalogs)
        syncs.drop(1).forEach { assertEquals("Only the requested catalog may be deepened: $syncs", listOf(catalog), it.catalogs) }
        syncs.forEach { assertEquals("Never full-scope or compound sync: $syncs", 1, it.context.countTasks()) }
        for (other in listOf("primary", "lake_one", "lake_two") - catalog) {
            assertTrue("Unrelated catalog's objects loaded: $other", root().databases.first { it.name == other }.schemas.all { it.tables.isEmpty() && it.views.isEmpty() })
        }
    }

    private fun introspect(context: LoaderContext) {
        val outcome = AtomicReference<Result<Boolean>>()
        errors.newScope(testRootDisposable).launch {
            outcome.set(runCatching { DataSourceSyncManager.getInstance().tryPerformSync(context, stopRunning = false, merge = false) != null })
        }
        PlatformTestUtil.waitWithEventsDispatching("real JDBC introspection completes", { outcome.get() != null }, 60)
        assertTrue(context.toString(), outcome.get().getOrThrow())
        assertFalse("Introspection reported database errors: $context", context.errorHandler?.hasErrors() == true)
        errors.assertNone()
    }

    fun testRealGenericJdbcLoadsAttachedCatalogsAndSchemas() {
        namespaces()
        val root = root()
        val names = root.databases.map { it.name }
        assertTrue("Attached catalogs missing from real model: $names", names.containsAll(listOf("primary", "lake_one", "lake_two")))
        assertTrue(root.databases.createOrGet("primary").schemas.any { it.name == "web" })
        assertTrue(root.databases.createOrGet("primary").schemas.any { it.name == "audit" })
        assertTrue(root.databases.createOrGet("lake_one").schemas.any { it.name == "sales" })
        assertTrue(root.databases.createOrGet("lake_two").schemas.any { it.name == "sales" })
    }

    fun testCompletionBootstrapsAttachedCatalogFromClearedModel() {
        val editor = console()
        editor.run("SELECT 1")
        clearModel()
        editor.columns("SELECT * FROM lake_one.<caret>")
        editor.assertBanner(true)
        PlatformTestUtil.waitWithEventsDispatching("catalog completion discovers root then just the attached catalog's schemas", {
            rootOrNull()?.databases?.firstOrNull { it.name == "lake_one" }?.schemas?.any { it.name == "sales" } == true
        }, 15)
        val schemas = editor.columns("SELECT * FROM lake_one.<caret>")
        assertTrue(schemas.toString(), "sales" in schemas)
        assertDiscovery("lake_one", 2)
        assertTrue(root().databases.first { it.name == "lake_one" }.schemas.all { it.tables.isEmpty() })
        editor.columns("SELECT * FROM lake_one.sales.<caret>")
        waitLoaded("lake_one", "sales")
        assertTrue(editor.columns("SELECT e.<caret> FROM lake_one.sales.events e").contains("one_only"))
        editor.assertBanner(false)
        assertDiscovery("lake_one", 3)
        clearModel()
        editor.columns("SELECT * FROM lake_one.sales.<caret>")
        waitLoaded("lake_one", "sales")
        // Retained namespace scope can let the brief list include this catalog's schemas.
        assertTrue(syncs.toString(), syncs.size in 2..3)
        assertDiscovery("lake_one", syncs.size)
    }

    fun testRelativeSchemaCompletionBootstrapsTheCurrentCatalog() {
        val editor = console()
        editor.run("USE lake_two.sales")
        clearModel()
        editor.columns("SELECT * FROM sales.<caret>")
        editor.assertBanner(true)
        waitLoaded("lake_two", "sales")
        assertTrue(editor.columns("SELECT * FROM sales.ev<caret>").any { it.equals("events", true) })
        editor.assertBanner(false)
        assertDiscovery("lake_two", 3)
    }

    fun testDisconnectedEmptySourceDiscoversWithoutExecutingConsoleSql() {
        val editor = console()
        clearModel()
        editor.columns("SELECT * FROM lake_one.sales.<caret>")
        editor.assertBanner(true)
        waitLoaded("lake_one", "sales")
        assertTrue(editor.columns("SELECT e.<caret> FROM lake_one.sales.events e").contains("one_only"))
        assertDiscovery("lake_one", 3)
        assertEmpty("No user console query was executed to bootstrap metadata",
            finished.filterIsInstance<DataRequest.QueryRequest>().filter { it.owner === editor.console })
    }

    fun testSchemaOnlyCompletionUsesTheEngineDefaultCatalogFromAnEmptyModel() {
        val editor = console()
        clearModel()
        editor.columns("SELECT * FROM web.<caret>")
        waitLoaded("primary", "web")
        assertTrue(editor.columns("SELECT e.<caret> FROM web.events e").contains("primary_only"))
        assertTrue(syncs.toString(), syncs.size in 2..3)
        assertDiscovery("primary", syncs.size)
    }

    fun testUnknownQualifiedReferenceDiscoversOnlyItsAttachedNamespace() {
        val editor = console()
        editor.run("SELECT 1")
        clearModel()
        editor.references("SELECT one_only FROM lake_one.sales.events")
        editor.assertBanner(true)
        waitLoaded("lake_one", "sales")
        PlatformTestUtil.waitWithEventsDispatching("bootstrap banner finishes", {
            project.getService(DuckdbAutoIntrospectService::class.java).discovery.pending(editor.file) == null
        }, 10)
        editor.assertBanner(false)
        assertDiscovery("lake_one", 3)
        editor.assertClean("SELECT one_only FROM lake_one.sales.events")
    }

    fun testMissingCatalogAndSchemaDoNotLoopOrInventModelObjects() {
        val editor = console()
        editor.run("SELECT 1")
        clearModel()
        editor.columns("SELECT * FROM does_not_exist.sales.<caret>")
        PlatformTestUtil.waitWithEventsDispatching("the root list finishes even when the catalog does not exist", {
            rootOrNull()?.databases?.isNotEmpty() == true && project.getService(DuckdbAutoIntrospectService::class.java).discovery.pending(editor.file) == null
        }, 30)
        assertEquals(syncs.toString(), 1, syncs.size)
        repeat(3) {
            editor.columns("SELECT * FROM does_not_exist.sales.<caret>")
            editor.references("SELECT * FROM does_not_exist.sales.events")
        }
        dispatchBriefly()
        assertEquals("A nonexistent catalog must not relist the root", 1, syncs.size)
        editor.columns("SELECT * FROM lake_one.missing_schema.<caret>")
        PlatformTestUtil.waitWithEventsDispatching("the catalog is listed once for an unknown schema", {
            root().databases.first { it.name == "lake_one" }.schemas.isNotEmpty() &&
                project.getService(DuckdbAutoIntrospectService::class.java).discovery.pending(editor.file) == null
        }, 30)
        repeat(3) {
            editor.columns("SELECT * FROM lake_one.missing_schema.<caret>")
            editor.references("SELECT * FROM lake_one.missing_schema.events")
        }
        dispatchBriefly()
        assertDiscovery("lake_one", 2)
        assertTrue(root().databases.none { it.name == "does_not_exist" })
        assertTrue(root().databases.first { it.name == "lake_one" }.schemas.none { it.name == "missing_schema" })
        editor.assertBanner(false)
        editor.references("SELECT * FROM lake_one.sales.missing_table")
        waitLoaded("lake_one", "sales")
        repeat(3) { editor.references("SELECT * FROM lake_one.sales.missing_table") }
        dispatchBriefly()
        assertDiscovery("lake_one", 3)
        assertTrue(root().databases.first { it.name == "lake_one" }.schemas.first { it.name == "sales" }.tables.none { it.name == "missing_table" })
    }

    fun testExplicitTransactionBlocksDiscoveryUntilCommit() {
        val editor = console()
        editor.run("BEGIN TRANSACTION")
        clearModel()
        editor.columns("SELECT * FROM lake_one.sales.<caret>")
        editor.references("SELECT * FROM lake_one.sales.events")
        dispatchBriefly()
        assertEmpty(syncs)
        assertEmpty(target.model.modelRoots.toList())
        editor.assertBanner(false)
        editor.run("COMMIT")
        editor.columns("SELECT * FROM lake_one.sales.<caret>")
        waitLoaded("lake_one", "sales")
        assertDiscovery("lake_one", 3)
    }

    fun testManualCommitModeAlsoBlocksAlreadyListedTargets() {
        namespaces()
        val editor = console()
        editor.run("SELECT 1")
        editor.session.setAutoCommit(false)
        editor.run("SELECT 1")
        syncs.clear()
        assertTrue(editor.session.currentTx != DataRequest.AUTO_COMMIT)
        editor.columns("SELECT * FROM lake_one.sales.<caret>")
        editor.references("SELECT * FROM lake_one.sales.events")
        dispatchBriefly()
        assertEmpty(syncs)
        assertTrue(root().databases.first { it.name == "lake_one" }.schemas.first { it.name == "sales" }.tables.isEmpty())
        editor.assertBanner(false)
        editor.run("ROLLBACK")
        // ROLLBACK does not switch a manual-commit console into auto-commit mode.
        editor.columns("SELECT * FROM lake_one.sales.<caret>")
        dispatchBriefly()
        assertEmpty(syncs)
    }

    fun testCompletionReferenceUseAndRefreshReachTheRealJdbcModel() {
        namespaces()
        val editor = console()
        editor.run("SELECT 1")
        editor.columns("SELECT * FROM lake_one.sales.ev<caret>")
        waitLoaded("lake_one", "sales")
        val first = editor.columns("SELECT e.<caret> FROM lake_one.sales.events e")
        assertTrue(first.toString(), first.containsAll(listOf("id", "label", "one_only")))
        assertFalse(first.toString(), "two_only" in first)
        assertTrue(root().databases.createOrGet("lake_one").schemas.createOrGet("sales").views.any { it.name == "event_view" })
        editor.assertClean("SELECT e.id, e.one_only FROM lake_one.sales.events e")

        editor.open("SELECT * FROM lake_two.sales.events")
        val registered = com.intellij.lang.LanguageAnnotators.INSTANCE.allForLanguage(DuckdbSqlDialect).single { it.javaClass.name.endsWith(".DuckdbReferenceIntrospectAnnotator") }
        val holder = com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl(com.intellij.lang.annotation.AnnotationSession(editor.psi), false)
        holder.runAnnotatorWithContext(editor.psi, com.intellij.lang.annotation.Annotator { _, annotations -> registered.annotate(editor.psi, annotations) })
        holder.assertAllAnnotationsCreated()
        val requests = project.getService(DuckdbAutoIntrospectService::class.java)
        val scopeState = requests.engine.state(dev.brikk.house.sql.intellij.catalog.AutoIntrospectEngine.Key(target.uniqueId, listOf("lake_two", "sales")))
        assertNotNull("Reference did not claim namespace; tables=" + root().databases.createOrGet("lake_two").schemas.createOrGet("sales").tables.map { "${it.name}:${it.columns.map { column -> column.name }}" }.toList(), scopeState)
        val banner = com.intellij.ui.EditorNotificationProvider.EP_NAME.getExtensions(project).filterIsInstance<DuckdbIntrospectBannerProvider>().single()
        assertNotNull("Active real reference refresh must show its banner", banner.collectNotificationData(project, editor.file))
        waitLoaded("lake_two", "sales")
        assertNull("Completed real refresh must clear its banner", banner.collectNotificationData(project, editor.file))

        editor.run("USE lake_two.sales")
        PlatformTestUtil.waitWithEventsDispatching("USE updates the actual console namespace", {
            editor.console.currentNamespace?.name == "sales" && editor.console.currentNamespace?.parent?.name == "lake_two"
        }, 30)
        val second = editor.columns("SELECT e.<caret> FROM events e")
        assertTrue(second.toString(), "two_only" in second)
        assertFalse(second.toString(), "one_only" in second)
        editor.assertClean("SELECT id, two_only FROM events")
        val macros = editor.columns("SELECT plus_o<caret>(1)")
        assertTrue("Live macro harvest missing: $macros", macros.any { it.equals("plus_one", true) })

        editor.run("CREATE TABLE created_after_refresh(added_after_refresh INT)")
        PlatformTestUtil.waitWithEventsDispatching("DDL refresh updates the real column model", {
            root().databases.createOrGet("lake_two").schemas.createOrGet("sales").tables.any { it.name == "created_after_refresh" && it.columns.any { column -> column.name == "added_after_refresh" } } &&
                !DataSourceSyncManager.getInstance().isActive(target)
        }, 60)
        editor.assertClean("SELECT added_after_refresh FROM created_after_refresh")
        editor.run("ALTER TABLE events ADD COLUMN altered_after_refresh INT")
        PlatformTestUtil.waitWithEventsDispatching("ALTER refresh reloads existing table columns", {
            root().databases.createOrGet("lake_two").schemas.createOrGet("sales").tables.createOrGet("events").columns.any { it.name == "altered_after_refresh" } &&
                !DataSourceSyncManager.getInstance().isActive(target)
        }, 60)
        editor.assertClean("SELECT altered_after_refresh FROM events")
        editor.columns("SELECT * FROM \"primary\".audit.ev<caret>")
        waitLoaded("primary", "audit")
        editor.run("USE \"primary\".audit")
        PlatformTestUtil.waitWithEventsDispatching("USE switches between schemas of the same database", {
            editor.console.currentNamespace?.name == "audit" && editor.console.currentNamespace?.parent?.name == "primary"
        }, 30)
        val audit = editor.columns("SELECT e.<caret> FROM events e")
        assertTrue(audit.toString(), "audit_only" in audit)
        assertFalse(audit.toString(), "primary_only" in audit || "two_only" in audit)
        editor.assertClean("SELECT audit_only FROM events")
    }
}
