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
        setSqlDialectMapping(project, null, DuckdbSqlDialect)
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun error(context: DataRequest.Context, error: ErrorInfo) { failures += error.toString() }
            override fun requestFinished(context: DataRequest.Context) { finished += context.request }
        }, testRootDisposable)
        val observer = DuckdbExecutionObserver(project, errors.newScope(testRootDisposable))
        Disposer.register(testRootDisposable, observer)
        observer.start()
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
            val namespace = root().databases.firstOrNull { it.name == catalog }?.schemas?.firstOrNull { it.name == schema }
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
