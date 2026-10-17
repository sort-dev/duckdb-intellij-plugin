package dev.sort.duckdb.catalog

import com.intellij.database.DataBus
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.connection.throwable.info.ErrorInfo
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.dialects.DatabaseDialect
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ModelFactory
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import dev.brikk.house.sql.intellij.catalog.CatalogSource
import dev.brikk.house.sql.intellij.catalog.RefreshOutcome
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

class DuckdbLiveCatalogConsoleTest : DuckdbCatalogTestCase() {
    private inner class Console(val virtualFile: VirtualFile, val session: DatabaseSession, val console: JdbcConsole) {
        val file: PsiFile get() = checkNotNull(PsiManager.getInstance(project).findFile(virtualFile))
    }

    private lateinit var target: LocalDataSource
    private lateinit var driver: DatabaseDriverImpl
    private val consoles = ArrayList<Console>()
    private val finished = CopyOnWriteArrayList<DataRequest>()
    private val executionErrors = CopyOnWriteArrayList<String>()
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        project.basePath?.let { Files.createDirectories(Path.of(it)) }
        val jar = System.getProperty("java.class.path").split(File.pathSeparator).first { "duckdb_jdbc" in it }
        driver = DatabaseDriverManager.getInstance().createDriver("catalog-${UUID.randomUUID()}", "Catalog DuckDB",
            UrlTemplate("default", "jdbc:duckdb:{file}")) as DatabaseDriverImpl
        driver.setDriverClass("org.duckdb.DuckDBDriver")
        driver.setSqlDialect("DuckDBSQL")
        driver.setForcedDbms(DuckdbDbms.DUCKDB_BRIKK)
        driver.setVmOptions("-Djava.io.tmpdir=${directory.toAbsolutePath()}")
        driver.setAdditionalClasspathElements(SimpleClasspathElementFactory.createElements("jar://$jar!/"))
        target = source().apply {
            databaseDriver = driver
            url = "jdbc:duckdb:${directory.resolve("console.db").toAbsolutePath()}"
        }
        setSqlDialectMapping(project, null, DuckdbSqlDialect)
        DbPsiFacade.getInstance(project).clearCaches()
        val observer = DuckdbExecutionObserver(project, errors.newScope(testRootDisposable))
        Disposer.register(testRootDisposable, observer)
        observer.start()
        DataBus.addRootAuditor(project, object : DataAuditor {
            override fun error(context: DataRequest.Context, error: ErrorInfo) {
                executionErrors += error.toString()
            }
            override fun requestFinished(context: DataRequest.Context) {
                finished += context.request
            }
        }, testRootDisposable)
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "DuckDB (Brikk)") notifications += notification
            }
        })
    }

    override fun tearDown() = RunAll(
        { consoles.forEach { DatabaseSessionViewService.getInstance().destroyView(it.console); Disposer.dispose(it.console); Disposer.dispose(it.session) } },
        { notifications.forEach(Notification::expire) },
        { assertEmpty(executionErrors) },
        { super.tearDown() },
        { if (::driver.isInitialized) DatabaseDriverManager.getInstance().removeDriver(driver) },
    ).run()

    private fun console(name: String): Console {
        val file = myFixture.addFileToProject("$name-${UUID.randomUUID()}.sql", "SELECT 1")
        val session = DatabaseSessionManager.openSession(project, target, name)
        val console = JdbcConsole.newConsole(project).forFile(file.virtualFile).fromDataSource(target).useSession(session).build()
        assertTrue(console.isValid)
        return Console(file.virtualFile, session, console).also { consoles += it }
    }

    private fun Console.run(sql: String) {
        myFixture.configureFromExistingVirtualFile(virtualFile)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(sql) }
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
        val request = requireNotNull(ConsoleDataRequest.newConsoleRequest(console, myFixture.editor, console.scriptModel, false))
        session.messageBus.dataProducer.processRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("'$sql' finishes", { request in finished }, 30)
        assertEmpty(executionErrors)
        errors.assertNone()
    }

    private fun Console.functions() = catalog.completion(file).functions
    private fun Console.sessionFunctions() = catalog.completion(file).selection.session.functions.map { it.def.name }

    private fun Console.refreshFromEditor() {
        val before = notifications.size
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, file).build()
        DuckdbRefreshLiveCatalogAction().actionPerformed(TestActionEvent.createTestEvent(context))
        PlatformTestUtil.waitWithEventsDispatching("manual catalog refresh finishes", { notifications.size > before }, 30)
        errors.assertNone()
    }

    private fun extensionDirectory(): Path = Files.createDirectories(Path.of("build", "catalog-extensions")).toAbsolutePath()

    private fun installFts() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use {
                it.execute("SET extension_directory = '${extensionDirectory()}'")
                it.execute("INSTALL fts FROM 'https://extensions.duckdb.org'")
            }
        }
    }

    fun testConsoleSwitchesAndReadsBackQuotedAttachedCatalogAndSchema() {
        val a = console("namespace")
        val catalog = "lake\"{schema}"
        val schema = "web.\"space"
        a.run("ATTACH ':memory:' AS \"lake\"\"{schema}\"")
        a.run("CREATE SCHEMA \"lake\"\"{schema}\".\"web.\"\"space\"")
        val dialect = DatabaseDialect.EP.forDbms(DuckdbDbms.DUCKDB_BRIKK) as dev.sort.duckdb.DuckdbDatabaseDialect
        val path = SearchPath.of(ObjectPath.create(catalog, ObjectKind.DATABASE).append(schema, ObjectKind.SCHEMA))
        assertEquals("USE \"lake\"\"{schema}\".\"web.\"\"space\"", dialect.sqlSetSearchPath(path))
        a.console.switchSchema(path, false)
        PlatformTestUtil.waitWithEventsDispatching("DuckDB console switches its schema", {
            a.console.currentNamespace?.name == schema && a.console.currentNamespace?.parent?.name == catalog
        }, 30)
        assertEquals(listOf(catalog, schema), a.readNamespace())
        assertEmpty(executionErrors)
        errors.assertNone()
        a.run(requireNotNull(dialect.sqlSetSearchPath(SearchPath.of(ObjectPath.create("console", ObjectKind.DATABASE)))))
        assertEquals(listOf("console", "main"), a.readNamespace())
        PlatformTestUtil.waitWithEventsDispatching("DuckDB console reads back a catalog-only switch", {
            a.console.currentNamespace?.name == "main" && a.console.currentNamespace?.parent?.name == "console"
        }, 30)
    }

    fun testOutOfScopeReferenceIntrospectionIsEnabled() {
        assertTrue(DuckdbCatalogProfile.outOfScopeReferences)
        assertTrue(com.intellij.lang.LanguageAnnotators.INSTANCE.allForLanguage(DuckdbSqlDialect).any {
            it.javaClass.name == "dev.sort.duckdb.catalog.DuckdbReferenceIntrospectAnnotator"
        })
    }

    private fun Console.readNamespace(): List<String> {
        var result: SearchPath? = null
        val dialect = DatabaseDialect.EP.forDbms(DuckdbDbms.DUCKDB_BRIKK) as dev.sort.duckdb.DuckdbDatabaseDialect
        val request = object : DataRequest.RawRequest(session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                result = dialect.tryToLoadSearchPath(connection)
            }
        }
        session.messageBus.dataProducer.processRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("DuckDB namespace is read back", { request in finished }, 30)
        assertEmpty(executionErrors)
        errors.assertNone()
        return generateSequence(requireNotNull(result).current) { it.parent }.mapNotNull { it.name }.toList().asReversed()
    }

    fun testMacroDefinitionLoadsListParametersThroughTheUserDriverProcess() {
        val a = console("definitions")
        a.run("CREATE MACRO main.definition_macro(\"a, b\", c) AS c")
        val namespace = a.readNamespace()
        val model = ModelFactory.BLACK_HOLE.createModel(DuckdbDbms.DUCKDB_BRIKK)
        val main = (model.root as PgRoot).databases.createOrGet(namespace.first()).schemas.createOrGet("main")
        val definition = StringBuilder()
        val request = object : DataRequest.RawRequest(a.session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                DuckdbDefinitionProvider().load(listOf(main.routines.createOrGet("definition_macro")), connection, definition)
            }
        }
        a.session.messageBus.dataProducer.processRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("DuckDB macro definition is loaded", { request in finished }, 30)
        assertEmpty(executionErrors)
        errors.assertNone()
        assertTrue(definition.toString(), definition.contains("CREATE MACRO \"main\".\"definition_macro\"(\"a, b\", \"c\") AS c;"))
        assertEquals(namespace, a.readNamespace())
    }

    fun testTemporaryMacrosCompleteOnlyInTheirOwnSessionAndSurviveHelperRefresh() {
        val a = console("a")
        a.run("CREATE TEMP MACRO only_in_a(x) AS x + 1")
        val b = console("b")
        b.run("CREATE TEMP MACRO only_in_b(x) AS x + 2")
        assertTrue("only_in_a" in a.sessionFunctions())
        assertFalse("only_in_b" in a.sessionFunctions())
        assertTrue("only_in_b" in b.sessionFunctions())
        assertFalse("only_in_a" in b.sessionFunctions())
        assertTrue(runBlocking { catalog.refresh(project, target) } is RefreshOutcome.Refreshed)
        assertTrue("only_in_a" in a.sessionFunctions())
        assertFalse("only_in_b" in a.functions().map { it.name })
        myFixture.configureFromExistingVirtualFile(a.virtualFile)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("SELECT only_in_") }
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        val items = myFixture.completeBasic().orEmpty()
        assertTrue("temporary macro completion: ${items.map { it.lookupString }}; ${myFixture.editor.document.text}",
            items.any { it.lookupString == "only_in_a" && it.`object` is FunctionPresentation } || myFixture.editor.document.text.contains("only_in_a("))
        assertTrue("macro DDL must not show a balloon", notifications.isEmpty())
    }

    fun testLoadedExtensionSurvivesHelperRefreshWithoutLeakingToAnotherSessionOrDisk() {
        installFts()
        val b = console("b")
        b.run("SELECT 1")
        val a = console("a")
        a.run("SET extension_directory = '${extensionDirectory()}'")
        a.run("LOAD fts")
        assertTrue("stem" in a.sessionFunctions())
        PlatformTestUtil.waitWithEventsDispatching("LOAD notification", { notifications.isNotEmpty() }, 10)
        assertTrue(notifications.last().content.contains("fts"))
        assertTrue(runBlocking { catalog.refresh(project, target) } is RefreshOutcome.Refreshed)
        assertTrue("stem" in a.sessionFunctions())
        assertFalse("stem" in b.sessionFunctions())
        assertFalse(catalog.selection(target).inventory!!.functions.any { it.def.name == "stem" })
        replaceCatalog()
        assertTrue(catalog.selection(target).source is CatalogSource.Disk)
        assertTrue(catalog.selection(target, a.session).session.functions.isEmpty())
    }

    fun testManualRefreshFromEditorDiscoversAutoloadForThatSessionOnly() {
        installFts()
        val b = console("b")
        b.run("SELECT 1")
        val a = console("a")
        a.run("SET extension_directory = '${extensionDirectory()}'")
        a.run("SELECT stem('running', 'porter')")
        assertFalse("autoload has no LOAD statement", "stem" in a.sessionFunctions())
        a.refreshFromEditor()
        assertTrue("stem" in a.sessionFunctions())
        assertTrue(a.functions().any { it.name == "stem" && it.extension == null })
        assertFalse("stem" in b.sessionFunctions())
        assertFalse(b.functions().any { it.name == "stem" && it.extension == null })
        assertTrue("notification must include the autoloaded extension", notifications.last().content.contains("fts"))
    }

    fun testManualAndObserverRefreshSkipAnOpenTransaction() {
        val a = console("a")
        a.run("CREATE TABLE written(x INT)")
        a.session.setAutoCommit(false)
        a.run("INSERT INTO written VALUES (1)")
        val prior = catalog.selection(target).inventory
        assertEquals(RefreshOutcome.SessionInTransaction, runBlocking { catalog.refresh(project, a.session) })
        a.run("CREATE TEMP MACRO in_transaction(x) AS x")
        assertSame(prior, catalog.selection(target).inventory)
        assertFalse("in_transaction" in a.sessionFunctions())
        a.run("COMMIT")
        assertEquals(1, committedRows())
    }

    fun testManualRollbackDiscardsWritesWithoutLeavingAStaleTransaction() {
        val a = console("a")
        a.run("CREATE TABLE written(x INT)")
        a.session.setAutoCommit(false)
        a.run("INSERT INTO written VALUES (1)")
        assertEquals(RefreshOutcome.SessionInTransaction, runBlocking { catalog.refresh(project, a.session) })
        a.run("ROLLBACK")
        assertEquals(0, committedRows())
    }

    private fun committedRows(): Int {
        val reader = console("readback")
        val count = AtomicInteger(-1)
        val request = object : DataRequest.RawRequest(reader.session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                val statement = requireNotNull(JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() })
                try {
                    val rows = requireNotNull(JdbcNativeUtil.computeRemote { statement.executeQuery("SELECT count(*) FROM written") })
                    try {
                        assertTrue(rows.next())
                        count.set(rows.getString(1).toInt())
                    } finally {
                        JdbcNativeUtil.performSafe { rows.close() }
                    }
                } finally {
                    JdbcNativeUtil.closeRemoteStatementSafe(statement)
                }
            }
        }
        reader.session.messageBus.dataProducer.processRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("committed rows are read in another session", { request in finished }, 30)
        assertEmpty(executionErrors)
        errors.assertNone()
        return count.get()
    }

    fun testTemporaryMacroShadowsTheBundledSignatureOnlyInItsSession() {
        val b = console("b")
        b.run("SELECT 1")
        val a = console("a")
        a.run("CREATE TEMP MACRO abs(x, y) AS x + y")
        val own = a.functions().single { it.name == "abs" }
        assertEquals("macro", own.nativeKind)
        assertEquals("ANY, ANY", own.signature)
        assertNull(b.functions().single { it.name == "abs" }.nativeKind)
    }
}
