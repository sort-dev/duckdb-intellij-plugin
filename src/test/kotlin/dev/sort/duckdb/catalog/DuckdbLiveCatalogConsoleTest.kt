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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import dev.brikk.house.sql.verify.VerifyResult
import dev.sort.duckdb.grammar.DuckdbGrammarProfile
import dev.sort.duckdb.grammar.DuckdbGrammarRuntime
import dev.sort.duckdb.grammar.DuckdbGrammarSettings
import dev.sort.duckdb.grammar.DuckdbGrammarVerification
import dev.sort.duckdb.validate.DuckdbNativeErrorProfile
import dev.sort.duckdb.pipes.DuckdbPipesSettings
import dev.sort.duckdb.pipes.DuckdbPipeDialect
import dev.brikk.house.sql.intellij.pipe.isEditingEnabled
import dev.brikk.house.sql.intellij.catalog.queryRows
import org.junit.Assert.assertThrows

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

    private fun Console.grammarRuntime(): DuckdbGrammarRuntime.Runtime {
        val runtime = project.service<DuckdbGrammarRuntime>()
        PlatformTestUtil.waitWithEventsDispatching("console grammar runtime is correlated", {
            runtime.runtime(target, session)?.snapshot?.supported == true
        }, 30)
        return requireNotNull(runtime.runtime(target, session))
    }

    private fun Console.verifyGrammar(sql: String): VerifyResult {
        val validator = requireNotNull(DuckdbNativeErrorProfile.validator(file))
        val future = ApplicationManager.getApplication().executeOnPooledThread<VerifyResult> { validator.verify(sql, null) }
        PlatformTestUtil.waitForFuture(future)
        return future.get()
    }

    private fun Console.scalar(sql: String): String? {
        var value: String? = null
        val request = object : DataRequest.RawRequest(session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                value = connection.queryRows(sql, 1, 5).firstOrNull()?.values?.firstOrNull()?.toString()
            }
        }
        session.messageBus.dataProducer.processRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("source scalar is read", { request in finished }, 30)
        assertEmpty(executionErrors)
        return value
    }

    private fun Console.connectionIdentity(): Pair<Any, Any> {
        var identity: Pair<Any, Any>? = null
        val request = object : DataRequest.RawRequest(session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                identity = connection to connection.remoteConnection
            }
        }
        session.messageBus.dataProducer.processRequest(request)
        PlatformTestUtil.waitWithEventsDispatching("actual source connection identity is captured", { request in finished }, 30)
        return requireNotNull(identity)
    }

    /** A controller double only. The configurable, its option editors and its Apply/reconnect code are real. */
    private inner class GrammarHost : AutoCloseable {
        val configurable = com.intellij.database.dataSource.DataSourceConfigurable(
            com.intellij.database.dataSource.LocalDataSourceManager.getInstance(project), target, project)
        val applyTasks = mutableListOf<com.intellij.openapi.progress.Progressive>()
        private val problems = mutableMapOf<Any?, Any>()
        private val userData = com.intellij.openapi.util.UserDataHolderBase()
        private val scope = errors.newScope(testRootDisposable)
        private val tracker = com.intellij.openapi.util.SimpleModificationTracker()
        private val controller = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(com.intellij.database.view.ui.DatabaseConfigEditor::class.java)) { proxy, method, args ->
            when (method.name) {
                "getSecretService" -> com.intellij.database.access.DatabaseCredentials.getInstance()
                "getModificationTracker" -> tracker
                "getCoroutineScope" -> scope
                "getModalityState" -> com.intellij.openapi.application.ModalityState.defaultModalityState()
                "getDataSources", "getAddLocalDataSourceActions" -> emptyList<Any>()
                "getMappings" -> com.intellij.util.containers.JBIterable.empty<Any>()
                "getTempTargetOrTarget" -> args!![0]
                "getConfigurable" -> null
                "createResetAction" -> object : com.intellij.openapi.actionSystem.AnAction() {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        (args!![0] as com.intellij.openapi.options.Configurable).reset()
                    }
                }
                "isObjectNameUnique" -> true
                "generateObjectName" -> args!![1]
                "getProblem" -> null
                "showErrorNotification" -> {
                    val message = args!![2]
                    if (message == null) problems.remove(args[1]) else problems[args[1]] = message
                    null
                }
                "getUserData" -> userData.getUserData(args!![0] as com.intellij.openapi.util.Key<Any>)
                "putUserData" -> { userData.putUserData(args!![0] as com.intellij.openapi.util.Key<Any>, args[1]); null }
                "onChangesApplied" -> { applyTasks += args!![0] as com.intellij.openapi.progress.Progressive; null }
                "onCreated", "onDataSourceUpdated", "onDriverDownloaded", "clearModifiedCache", "refresh", "processAction" -> null
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Grammar host controller"
                else -> error("Unexpected native controller call ${method.name}")
            }
        } as com.intellij.database.view.ui.DatabaseConfigEditor
        val component: javax.swing.JComponent
        val editor: dev.sort.duckdb.grammar.DuckdbGrammarEditor
        init {
            configurable.setController(controller)
            component = requireNotNull(configurable.createComponent())
            configurable.init { }
            configurable.onUserActivity()
            check(problems.isEmpty()) { "Native host configuration problems: $problems" }
            fun panels(c: java.awt.Component): List<dev.sort.duckdb.grammar.DuckdbGrammarEditor> =
                (if (c is dev.sort.duckdb.grammar.DuckdbGrammarEditor) listOf(c) else emptyList()) +
                    (c as? java.awt.Container)?.components.orEmpty().flatMap(::panels)
            editor = panels(component).single()
        }
        override fun close() { Disposer.dispose(configurable) }
    }

    fun testNativePsqlGrammarApplyPreservesFileConnections() = grammarApplyPreservesConnections(false)
    fun testNativePsqlGrammarApplyPreservesInMemoryConnection() = grammarApplyPreservesConnections(true)

    fun testNativePsqlKeywordCompletionLookupInsertionAndSafetyWithBrikkPipesOff() {
        val codeInsight = com.intellij.codeInsight.CodeInsightSettings.getInstance()
        val previousAuto = codeInsight.AUTOCOMPLETE_ON_CODE_COMPLETION
        codeInsight.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        val pipes = project.service<DuckdbPipesSettings>()
        val previousPipes = pipes.enabled
        pipes.enabled = false
        try {
            target.url = "jdbc:duckdb:identifier5.db"
            target.workingDir = directory.toAbsolutePath().toString()
            val originalUrl = target.url
            val a = console("DORIS-old-title-native-keywords")
            val binary = requireNotNull(System.getProperty("test.psqlExtension"))
            a.run("SET extension_directory = ${DuckdbGrammarProfile.literal(File(binary).parentFile.parentFile.parentFile.absolutePath)}")
            a.run("LOAD psql")
            a.grammarRuntime()
            val factory = project.service<DuckdbGrammarVerification>()
            val service = project.service<dev.sort.duckdb.completion.DuckdbNativeKeywordService>()
            fun complete(sql: String): dev.sort.duckdb.completion.NativeKeywords {
                val target = requireNotNull(factory.target(a.file))
                val prefix = requireNotNull(dev.sort.duckdb.completion.NativeKeywordPrefix.capture(sql, sql.length))
                val future = ApplicationManager.getApplication().executeOnPooledThread<dev.sort.duckdb.completion.NativeKeywords> { service.complete(target, prefix) }
                PlatformTestUtil.waitForFuture(future)
                return future.get()
            }
            fun lookups(sql: String): List<com.intellij.codeInsight.lookup.LookupElement> {
                com.intellij.codeInsight.lookup.LookupManager.getInstance(project).hideActiveLookup()
                FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
                val normalized = sql.replace("\r\n", "\n")
                val caret = normalized.indexOf("<caret>")
                WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(normalized.replace("<caret>", "")) }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
                myFixture.editor.caretModel.moveToOffset(caret)
                val items = myFixture.completeBasic().orEmpty().toList()
                FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
                return items
            }
            val header = "INSTALL psql FROM community;\nLOAD psql;\nSELECT * FROM duckdb_grammar_extensions();\n-- name: psql\n" +
                "SELECT current_setting('active_grammar_extensions');\n-- {psql}\n\n"
            val script = header + "FROM range(6) AS t(i)\n|> WH<caret>ERE i % 2 = 0\n|> SELECT i, i * 2 AS doubled\n|> ORDER BY i DESC;\n-- result: 4,8; 2,4; 0,0"
            assertEquals("DuckDBSQL", a.file.language.id)
            assertEquals(originalUrl, a.session.connectionPoint.dataSource.url)
            val beforeHelper = project.service<DuckdbGrammarRuntime>().stamp
            val missing = complete("FROM range(6) AS t(i) |> WH")
            assertTrue(missing.toString(), missing.status.orEmpty().contains("LOAD autocomplete"))
            assertTrue(missing.items.isEmpty())
            assertEquals("Opening a keyword helper must not change the user runtime revision", beforeHelper, project.service<DuckdbGrammarRuntime>().stamp)
            val noticeCount = notifications.size
            val missingItems = lookups(script)
            assertFalse(missingItems.any { com.intellij.codeInsight.lookup.LookupElementPresentation().also(it::renderElement).typeText == "native keyword" })
            PlatformTestUtil.waitWithEventsDispatching("missing autocomplete reason is visible outside an empty lookup", {
                notifications.drop(noticeCount).any { it.title == "Native keyword completion" && it.content.contains("LOAD autocomplete") }
            }, 30)
            val statusPanel = dev.sort.duckdb.grammar.DuckdbGrammarEditor(project, target.copy(true))
            assertTrue(statusPanel.completionStatus.text, statusPanel.completionStatus.text.contains("LOAD autocomplete"))
            assertEquals(script.replace("<caret>", ""), myFixture.editor.document.text)
            assertEquals("false", a.scalar("SELECT loaded::VARCHAR FROM system.main.duckdb_extensions() WHERE extension_name = 'autocomplete'"))
            a.run("LOAD autocomplete")
            a.run("CREATE SEQUENCE native_completion_untouched START 42")
            for ((prefix, expected) in listOf(
                "FROM range(6) AS t(i) |> WH" to "WHERE",
                "FROM range(6) AS t(i) |> SEL" to "SELECT",
                "FROM range(6) AS t(i) |> SELECT i |> ORD" to "ORDER",
                "SELECT '😀' AS x |> WH" to "WHERE",
                "SELECT '😀' AS x\r\n|> WH" to "WHERE",
                "FROM range(nextval('native_completion_untouched')) |> WH" to "WHERE",
            )) {
                val result = complete(prefix)
                assertTrue("$prefix -> $result", result.items.any { it.word.equals(expected, true) })
            }
            assertEquals("42", a.scalar("SELECT nextval('native_completion_untouched')"))
            fun native(item: com.intellij.codeInsight.lookup.LookupElement) =
                com.intellij.codeInsight.lookup.LookupElementPresentation().also(item::renderElement).typeText == "native keyword"
            for ((sql, expected) in listOf(
                script to script.replace("<caret>", ""),
                script.replace("WH<caret>ERE", "<caret>WHERE") to script.replace("<caret>", ""),
                script.replace("WH<caret>ERE", "WH<caret>") to script.replace("<caret>", ""),
                "FROM range(6) AS t(i) |> WH<caret> i > 0; SELECT 999 AS PRIVATE_SUFFIX" to "FROM range(6) AS t(i) |> WHERE i > 0; SELECT 999 AS PRIVATE_SUFFIX",
                "SELECT '😀' AS x\r\n|> WH<caret> x IS NOT NULL" to "SELECT '😀' AS x\n|> WHERE x IS NOT NULL",
                "FROM range(6) |> SEL<caret> i" to "FROM range(6) |> SELECT i",
                "FROM range(6) |> SELECT i |> ORD<caret> i" to "FROM range(6) |> SELECT i |> ORDER i",
            )) {
                val items = lookups(sql)
                val beforeCaret = sql.substringBefore("<caret>")
                val word = if (beforeCaret.endsWith("ORD")) "ORDER" else if (beforeCaret.endsWith("SEL")) "SELECT" else "WHERE"
                assertEquals("No duplicate $word keywords: ${items.map { it.lookupString }}", 1, items.count { it.lookupString.equals(word, true) })
                val item = items.single { native(it) && it.lookupString.equals(word, true) }
                myFixture.lookup.currentItem = item
                myFixture.finishLookup('\n')
                assertEquals(expected, myFixture.editor.document.text)
                FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
            }
            for (space in listOf("\u00a0", "\u2003")) {
                val sql = "SELECT '😀' AS x${space}|> WH<caret> x IS NOT NULL"
                assertFalse(lookups(sql).any(::native))
                assertEquals(sql.replace("<caret>", ""), myFixture.editor.document.text)
            }
            assertEquals(1, lookups("SELECT ab<caret>").count { it.lookupString.equals("abs", true) })
            val settings = project.service<DuckdbGrammarSettings>()
            settings.setProfile(target.uniqueId, DuckdbGrammarProfile(disabled = listOf("psql")))
            project.service<DuckdbGrammarRuntime>().changed()
            assertFalse(complete("FROM range(6) AS t(i) |> WH").items.any { it.word.equals("WHERE", true) })
            settings.setProfile(target.uniqueId, DuckdbGrammarProfile(listOf("psql")))
            project.service<DuckdbGrammarRuntime>().changed()
            val stale = requireNotNull(factory.target(a.file))
            settings.setProfile(target.uniqueId, DuckdbGrammarProfile(disabled = listOf("psql")))
            project.service<DuckdbGrammarRuntime>().changed()
            val p = requireNotNull(dev.sort.duckdb.completion.NativeKeywordPrefix.capture("SELECT WH", 9))
            assertTrue(service.complete(stale, p).items.isEmpty())
            settings.setProfile(target.uniqueId, DuckdbGrammarProfile(listOf("psql")))
            project.service<DuckdbGrammarRuntime>().changed()
            val fresh = requireNotNull(factory.target(a.file))
            var polls = 0
            assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
                service.complete(fresh, p) { if (++polls >= 2) throw com.intellij.openapi.progress.ProcessCanceledException() }
            }
            assertFalse(DuckdbPipeDialect.isEditingEnabled(a.file))
            assertEquals("Completion must never rewrite the saved relative URL", originalUrl, target.url)
        } finally {
            codeInsight.AUTOCOMPLETE_ON_CODE_COMPLETION = previousAuto
            pipes.enabled = previousPipes
        }
    }

    fun testNativePsqlMemoryCompletionExplainsUnsupportedModeForTheFullConsoleDocument() {
        target.url = "jdbc:duckdb:"
        project.service<DuckdbPipesSettings>().enabled = false
        val codeInsight = com.intellij.codeInsight.CodeInsightSettings.getInstance()
        val previousAuto = codeInsight.AUTOCOMPLETE_ON_CODE_COMPLETION
        codeInsight.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            val a = console("DORIS-old-title-memory-keywords")
            val binary = requireNotNull(System.getProperty("test.psqlExtension"))
            a.run("SET extension_directory = ${DuckdbGrammarProfile.literal(File(binary).parentFile.parentFile.parentFile.absolutePath)}")
            a.run("LOAD psql")
            a.grammarRuntime()
            val originalConnection = a.connectionIdentity()
            val document = "INSTALL psql FROM community;\nLOAD psql;\nSELECT * FROM duckdb_grammar_extensions();\n-- psql\n" +
                "SELECT current_setting('active_grammar_extensions');\n-- {psql}\n\nFROM range(6) AS t(i)\n|> WH<caret>ERE i % 2 = 0\n|> SELECT i\n|> ORDER BY i DESC;"
            val panel = dev.sort.duckdb.grammar.DuckdbGrammarEditor(project, target.copy(true))
            assertTrue(panel.completionStatus.text, panel.completionStatus.text.contains("in-memory"))
            assertTrue(panel.completionStatus.text, panel.completionStatus.text.contains("Loading autocomplete does not enable"))
            val before = notifications.size
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(document.replace("<caret>", "")) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
            myFixture.editor.caretModel.moveToOffset(document.indexOf("<caret>"))
            val items = myFixture.completeBasic().orEmpty()
            assertFalse(items.any { com.intellij.codeInsight.lookup.LookupElementPresentation().also(it::renderElement).typeText == "native keyword" })
            PlatformTestUtil.waitWithEventsDispatching("manual completion explains memory mode despite having no native suggestions", {
                notifications.drop(before).any { it.title == "Native keyword completion" && it.content.contains("in-memory") }
            }, 30)
            assertEquals("DuckDBSQL", a.file.language.id)
            assertEquals(document.replace("<caret>", ""), myFixture.editor.document.text)
            val after = a.connectionIdentity()
            assertSame(originalConnection.first, after.first)
            assertSame(originalConnection.second, after.second)
            assertEquals("jdbc:duckdb:", target.url)
            assertEquals("[\"psql\"]", a.scalar("SELECT system.main.to_json(system.main.current_setting('active_grammar_extensions'))"))
        } finally { codeInsight.AUTOCOMPLETE_ON_CODE_COMPLETION = previousAuto }
    }

    private fun grammarApplyPreservesConnections(memory: Boolean) {
        project.service<DuckdbPipesSettings>().enabled = false
        if (memory) target.url = "jdbc:duckdb:"
        // Canonical native defaults are established before connecting, not blamed on grammar edits.
        GrammarHost().use { it.configurable.apply() }
        val a = console("grammar-live-a")
        a.run("CREATE TEMP TABLE grammar_data AS SELECT 7 AS value")
        a.run("SET VARIABLE grammar_sentinel = 42")
        val b = if (memory) null else console("grammar-live-b").also {
            it.run("CREATE TEMP TABLE grammar_data AS SELECT 8 AS value")
            it.run("SET VARIABLE grammar_sentinel = 43")
        }
        val binary = requireNotNull(System.getProperty("test.psqlExtension"))
        a.run("SET extension_directory = ${DuckdbGrammarProfile.literal(File(binary).parentFile.parentFile.parentFile.absolutePath)}")
        a.run("LOAD psql")
        a.grammarRuntime()
        b?.grammarRuntime()
        val runtime = project.service<DuckdbGrammarRuntime>()
        val settings = project.service<DuckdbGrammarSettings>()
        fun settled() = PlatformTestUtil.waitWithEventsDispatching("live grammar application settles", { runtime.applicationStatus(target) == null }, 30)
        settled()
        assertEquals(listOf("psql"), settings.profile(target).enabled)
        val before = target.copy(true)
        val aIdentity = a.connectionIdentity()
        val bIdentity = b?.connectionIdentity()
        fun intact(active: Boolean) {
            val after = a.connectionIdentity()
            assertSame(aIdentity.first, after.first); assertSame(aIdentity.second, after.second)
            assertEquals("7", a.scalar("SELECT value FROM grammar_data"))
            assertEquals("42", a.scalar("SELECT getvariable('grammar_sentinel')"))
            assertEquals("psql", a.scalar("SELECT name FROM system.main.duckdb_grammar_extensions() WHERE name = 'psql'"))
            assertEquals("true", a.scalar("SELECT loaded::VARCHAR FROM system.main.duckdb_extensions() WHERE extension_name = 'psql'"))
            assertEquals(if (active) "[\"psql\"]" else "[]", a.scalar("SELECT system.main.to_json(system.main.current_setting('active_grammar_extensions'))"))
            if (b != null) {
                val other = b.connectionIdentity()
                assertSame(bIdentity!!.first, other.first); assertSame(bIdentity.second, other.second)
                assertEquals("8", b.scalar("SELECT value FROM grammar_data"))
                assertEquals("43", b.scalar("SELECT getvariable('grammar_sentinel')"))
                assertEquals(if (active) "[\"psql\"]" else "[]", b.scalar("SELECT system.main.to_json(system.main.current_setting('active_grammar_extensions'))"))
            }
            assertTrue("Grammar state must not change native connection configuration", before.equalConfiguration(target))
        }
        intact(true)
        GrammarHost().use { host ->
            assertFalse(host.configurable.isModified)
            val row = (0 until host.editor.model.rowCount).single { host.editor.model.getValueAt(it, 1) == "psql" }
            host.editor.model.setValueAt(false, row, 0)
            assertTrue(host.editor.applyChanges.isEnabled)
            assertFalse("Editing grammar choices must not dirty native connection settings", host.configurable.isModified)
            assertTrue(before.equalConfiguration(host.configurable.tempDataSourceForConnection))
            host.configurable.apply()
            assertTrue("Real host Apply must not schedule reconnect/introspection", host.applyTasks.isEmpty())
            assertEquals(listOf("psql"), settings.profile(target).enabled)
            assertEquals(emptyList<String>(), host.editor.profile().enabled)
            intact(true)
            host.editor.applyChanges.doClick()
            settled()
            intact(false)
            repeat(2) {
                host.editor.discover()
                PlatformTestUtil.waitWithEventsDispatching("disabled grammar remains disabled on discovery", { host.editor.discover.isEnabled }, 30)
                assertEquals(listOf("psql"), settings.profile(target).disabled)
                assertEquals(emptyList<String>(), settings.profile(target).enabled)
                intact(false)
            }
            val revision = runtime.stamp
            host.editor.applyGrammarChanges()
            assertEquals(revision, runtime.stamp)
            host.configurable.apply()
            assertTrue(host.applyTasks.isEmpty())
            intact(false)
            val disabledRow = (0 until host.editor.model.rowCount).single { host.editor.model.getValueAt(it, 1) == "psql" }
            host.editor.model.setValueAt(true, disabledRow, 0)
            host.editor.applyChanges.doClick()
            settled()
            intact(true)

            val busyStarted = java.util.concurrent.CountDownLatch(1)
            val releaseBusy = java.util.concurrent.CountDownLatch(1)
            val busy = object : DataRequest.RawRequest(a.console) {
                override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                    busyStarted.countDown()
                    check(releaseBusy.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            a.session.messageBus.dataProducer.processRequest(busy)
            PlatformTestUtil.waitWithEventsDispatching("source has an active user request", { busyStarted.count == 0L }, 30)
            try {
                host.editor.model.setValueAt(false, 0, 0)
                host.editor.applyChanges.doClick()
                assertTrue(runtime.applicationStatus(target).orEmpty(), runtime.applicationStatus(target).orEmpty().contains("busy"))
            } finally { releaseBusy.countDown() }
            PlatformTestUtil.waitWithEventsDispatching("active request finishes before live activation", { busy in finished }, 30)
            settled()
            intact(false)
            host.editor.model.setValueAt(true, 0, 0)
            host.editor.applyChanges.doClick()
            settled()
            intact(true)

            a.session.setAutoCommit(false)
            a.run("INSERT INTO grammar_data VALUES (99)")
            a.run("SET active_grammar_extensions = ['psql']")
            host.editor.model.setValueAt(false, 0, 0)
            host.editor.applyChanges.doClick()
            assertTrue(runtime.applicationStatus(target).orEmpty(), runtime.applicationStatus(target).orEmpty().contains("transaction"))
            assertFalse(host.configurable.isModified)
            host.configurable.apply()
            assertTrue("Native no-op Apply must not reset an open transaction", host.applyTasks.isEmpty())
            GrammarHost().use { cancelledInTransaction -> cancelledInTransaction.editor.addGrammar("psql") }
            val rollback = object : DataRequest.TxRequest(a.console, a.session.currentTx, DataRequest.TxCommand.ROLLBACK) {}
            a.session.messageBus.dataProducer.processRequest(rollback)
            PlatformTestUtil.waitWithEventsDispatching("user's IDE rollback releases the manual transaction", { rollback in finished }, 30)
            a.session.setAutoCommit(true)
            a.run("SELECT 1")
            settled()
            assertEquals("1", a.scalar("SELECT count(*) FROM grammar_data"))
            intact(false)
        }
        // Disposing a real native configurable represents Cancel, not an implicit grammar apply.
        GrammarHost().use { cancelled ->
            cancelled.editor.model.setValueAt(true, 0, 0)
            assertTrue(cancelled.editor.applyChanges.isEnabled)
        }
        assertEquals(emptyList<String>(), settings.profile(target).enabled)
        intact(false)
    }

    private fun Console.nativeErrors(sql: String): List<String> {
        myFixture.configureFromExistingVirtualFile(virtualFile)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(sql) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
        val annotator = dev.sort.duckdb.validate.DuckdbErrorAnnotator()
        val snapshot = annotator.collectInformation(file)
        val future = ApplicationManager.getApplication().executeOnPooledThread<dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator.Outcome?> { annotator.doAnnotate(snapshot) }
        PlatformTestUtil.waitForFuture(future)
        val annotations = com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl(com.intellij.lang.annotation.AnnotationSession(file), false)
        annotations.runAnnotatorWithContext(file, com.intellij.lang.annotation.Annotator { _, holder -> annotator.apply(file, future.get(), holder) })
        annotations.assertAllAnnotationsCreated()
        return annotations.filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }.map { it.message.orEmpty() }
    }

    /** Explicit native binary gate, run with -Ptest.psqlExtension=<signed DuckDB 2.0 binary>. */
    fun testNativePsqlGrammarManagedHelpersWithoutBrikkPipes() {
        val binary = requireNotNull(System.getProperty("test.psqlExtension"))
        assertTrue(File(binary).isFile)
        val pipes = project.service<DuckdbPipesSettings>()
        val previousPipes = pipes.enabled
        val settings = project.service<DuckdbGrammarSettings>()
        val runtime = project.service<DuckdbGrammarRuntime>()
        pipes.enabled = false
        try {
            val a = console("native-psql-grammar")
            a.run("SELECT 1")
            a.grammarRuntime()
            assertFalse("The plugin's PIPE feature must not lower this SQL", DuckdbPipeDialect.isEditingEnabled(a.file))
            val installedDirectory = File(binary).parentFile.parentFile.parentFile.absolutePath
            a.run("SET extension_directory = ${DuckdbGrammarProfile.literal(installedDirectory)}")
            // LOAD by its explicitly approved module name retains the installed community provenance.
            a.run("LOAD psql")
            val state = a.grammarRuntime()
            assertTrue(state.snapshot.available.toString(), "psql" in state.snapshot.available)
            assertEquals(listOf("psql"), state.snapshot.effective)
            assertEquals(listOf("psql"), settings.profile(target.uniqueId).enabled)

            val provider = dev.sort.duckdb.grammar.DuckdbGrammarOptionProvider()
            val sourceDraft = target.copy(true)
            val desc = provider.getEditor(sourceDraft, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build())
            val panel = desc.component as dev.sort.duckdb.grammar.DuckdbGrammarEditor
            assertTrue("Opening the tab must show the connected source's discovered grammars without manual entry",
                (0 until panel.model.rowCount).any { panel.model.getValueAt(it, 1) == "psql" })
            panel.discover()
            PlatformTestUtil.waitWithEventsDispatching("real native-tab discovery finds psql", {
                panel.discover.isEnabled && (0 until panel.model.rowCount).any { panel.model.getValueAt(it, 1) == "psql" }
            }, 30)
            assertEquals("psql", panel.model.getValueAt(0, 1))
            assertEquals(true, panel.model.getValueAt(0, 0))
            assertEquals("Available", panel.model.getValueAt(0, 2))
            assertEquals(listOf("psql"), settings.profile(target.uniqueId).enabled)

            // Keep the user's session explicitly opted out while helpers use managed activation.
            a.run("RESET active_grammar_extensions")
            assertEquals(emptyList<String>(), state.snapshot.effective)
            a.run("CREATE SEQUENCE native_psql_untouched START 42")
            val sql = "FROM range(6) t(i) |> WHERE i % 2 = 0 |> SELECT i, i * 2 AS doubled |> ORDER BY i DESC"
            settings.setProfile(target.uniqueId, DuckdbGrammarProfile(listOf("psql")))
            runtime.changed()
            assertEquals(VerifyResult.Accepted, a.verifyGrammar(sql))
            assertEmpty("The editor must accept native psql syntax without client PIPE lowering", a.nativeErrors(sql))
            assertTrue(a.verifyGrammar("FROM range(6) t(i) |> WHERE |> SELECT i") is VerifyResult.Rejected)
            assertEquals(VerifyResult.Accepted, a.verifyGrammar("FROM range(nextval('native_psql_untouched')) |> SELECT *"))
            assertEquals("42", a.scalar("SELECT nextval('native_psql_untouched')"))
            assertEquals("[]", a.scalar("SELECT system.main.to_json(system.main.current_setting('active_grammar_extensions'))"))

            settings.setProfile(target.uniqueId, DuckdbGrammarProfile(disabled = listOf("psql")))
            runtime.changed()
            val disabled = a.verifyGrammar(sql)
            assertTrue("Deactivation must give a real native rejection, not client lowering or silent unavailability: $disabled", disabled is VerifyResult.Rejected)
            assertFalse("The same unchanged editor SQL must receive native syntax errors when psql is disabled", a.nativeErrors(sql).isEmpty())
            assertFalse(DuckdbPipeDialect.isEditingEnabled(a.file))
        } finally {
            settings.setProfile(target.uniqueId, DuckdbGrammarProfile())
            runtime.changed()
            pipes.enabled = previousPipes
        }
    }

    fun testManagedGrammarRuntimeAndHelperUseTheActualFileConsole() {
        val a = console("grammar-managed")
        a.run("SELECT 1")
        val state = a.grammarRuntime()
        assertTrue(state.snapshot.supported)
        assertEquals(emptyList<String>(), state.snapshot.effective)
        assertNull(state.initializationError)
        assertEquals(VerifyResult.Accepted, a.verifyGrammar("SELECT 42"))
        val text = "SELECT '😊'\r\nFROM WHERE"
        val rejected = a.verifyGrammar(text) as VerifyResult.Rejected
        assertEquals(text.indexOf("WHERE"), rejected.error.offset)
        val before = project.service<DuckdbGrammarRuntime>().stamp
        a.run("SET active_grammar_extensions = []")
        assertTrue(project.service<DuckdbGrammarRuntime>().stamp > before)
        assertSame(state, a.grammarRuntime())
        assertEquals(emptyList<String>(), state.snapshot.effective)
    }

    fun testGrammarDiscoveryDiscardsQueuedResultsAndRejectsAnOldConnectionLogin() {
        val a = console("grammar-stale-login")
        a.run("SELECT 1")
        a.grammarRuntime()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val hold = object : DataRequest.RawRequest(a.session) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                started.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        a.session.messageBus.dataProducer.processRequest(hold)
        PlatformTestUtil.waitWithEventsDispatching("source engine is held before queued discovery", { started.count == 0L }, 30)
        try {
            val draft = target.copy(true)
            val panel = dev.sort.duckdb.grammar.DuckdbGrammarEditor(project, draft)
            panel.discover()
            assertFalse(panel.discover.isEnabled)
            draft.url = "jdbc:duckdb:${directory.resolve("different-login.db").toAbsolutePath()}"
            release.countDown()
            PlatformTestUtil.waitWithEventsDispatching("stale discovery is discarded", { panel.discover.isEnabled }, 30)
            assertTrue(panel.status.text, panel.status.text.contains("changed"))
            assertEquals(0, panel.model.rowCount)
            val original = target.url
            try {
                target.url = draft.url
                val runtime = project.service<DuckdbGrammarRuntime>()
                assertFalse(runtime.canDiscover(target))
                val discovery = runtime.discover(target)
                assertTrue("A stale login must be refused without queuing an engine request", discovery.isDone)
                val result = discovery.get()
                assertFalse(result.supported)
                assertTrue(result.unavailable.orEmpty(), result.unavailable.orEmpty().contains("changed"))
            } finally { target.url = original }
        } finally { release.countDown() }
    }

    fun testMissingManagedGrammarIsUnavailableWithoutStaticSyntaxFallback() {
        val a = console("grammar-missing")
        a.run("SELECT 1")
        a.grammarRuntime()
        val settings = project.service<DuckdbGrammarSettings>()
        try {
            settings.setProfile(target.uniqueId, DuckdbGrammarProfile(listOf("not_registered")))
            project.service<DuckdbGrammarRuntime>().changed()
            val result = a.verifyGrammar("FROBULATE mango")
            assertTrue(result.toString(), result is VerifyResult.Unavailable)
            assertFalse(DuckdbNativeErrorProfile.localParser)
            assertFalse(DuckdbNativeErrorProfile.reportsPsiErrors)
            myFixture.configureFromExistingVirtualFile(a.virtualFile)
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("FROBULATE mango") }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEmpty(myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR })
        } finally {
            settings.setProfile(target.uniqueId, DuckdbGrammarProfile())
            project.service<DuckdbGrammarRuntime>().changed()
        }
    }

    fun testManagedHelperDiscardsSameUrlConfigurationChangesWithoutTouchingTheConsole() {
        val a=console("managed-config")
        a.run("CREATE TEMP TABLE preserved_config(n INTEGER)")
        a.grammarRuntime()
        val original=a.connectionIdentity()
        val factory=project.service<DuckdbGrammarVerification>()
        val before=requireNotNull(factory.target(a.file))
        assertTrue(factory.isCurrent(before))
        val url=target.url
        target.workingDir=directory.resolve("changed-working-directory").toString()
        assertFalse(factory.isCurrent(before))
        assertNull(factory.openHelper(before))
        assertTrue(a.verifyGrammar("SELECT 42") is VerifyResult.Unavailable)
        assertTrue(requireNotNull(factory.identity(a.file)).unavailable.orEmpty().contains("configuration changed"))
        assertEquals(url,target.url)
        assertSame(original.first,a.connectionIdentity().first)
        assertSame(original.second,a.connectionIdentity().second)
        assertEquals("1",a.scalar("SELECT count(*) FROM duckdb_tables() WHERE temporary AND table_name='preserved_config'"))
    }

    fun testManagedHelperDoesNotClaimIsolationWhenSingleConnectionModeIsEnabled() {
        target.isSingleConnection=true
        val a=console("managed-single-connection")
        a.run("SELECT 1");a.grammarRuntime()
        assertTrue(a.verifyGrammar("SELECT 42") is VerifyResult.Unavailable)
        assertTrue(requireNotNull(project.service<DuckdbGrammarVerification>().identity(a.file)).unavailable.orEmpty().contains("single-connection"))
    }

    fun testManagedHelperRejectsReplacedManualModuleWithoutChangingTheSourceUrl() {
        val marker=directory.resolve("driver-module-marker.jar").toAbsolutePath()
        val manifest=java.util.jar.Manifest().apply { mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION]="1.0" }
        java.util.jar.JarOutputStream(Files.newOutputStream(marker),manifest).use {
            it.putNextEntry(java.util.jar.JarEntry("module-fixture.txt"));it.write("before".toByteArray());it.closeEntry()
        }
        driver.setAdditionalClasspathElements(driver.additionalClasspathElements + SimpleClasspathElementFactory.createElements("jar://$marker!/"))
        val a=console("managed-module-change")
        a.run("SELECT 1");a.grammarRuntime()
        val factory=project.service<DuckdbGrammarVerification>()
        val before=requireNotNull(factory.target(a.file))
        assertTrue(factory.isCurrent(before))
        val originalUrl=target.url
        Files.writeString(marker,"replaced module contents")
        assertFalse(factory.isCurrent(before))
        assertNull(factory.openHelper(before))
        assertTrue(requireNotNull(factory.identity(a.file)).unavailable.orEmpty().contains("module files changed"))
        assertTrue(a.verifyGrammar("SELECT 42") is VerifyResult.Unavailable)
        assertEquals(originalUrl,target.url)
        assertEquals("42",a.scalar("SELECT 42"))
    }

    fun testManagedRoutedConsoleCannotBorrowAnotherConsolesRuntime() {
        val a=console("managed-routed-a")
        val b=console("managed-unrouted-b")
        a.run("SELECT 1");b.run("SELECT 1")
        val first=a.grammarRuntime();val second=b.grammarRuntime()
        val runtime=project.service<DuckdbGrammarRuntime>()
        val factory=project.service<DuckdbGrammarVerification>()
        val old=requireNotNull(factory.target(a.file))
        // Model the runtime flag produced by CONNECT without opening a remote
        // server or pretending this test qualifies live routing itself.
        first.routed=true
        assertFalse("A routing change is unsafe even before the revision notification",factory.isCurrent(old))
        runtime.changed()
        b.run("SET active_grammar_extensions=[]")
        assertSame(second,runtime.runtime(target))
        val routed=requireNotNull(factory.target(a.file))
        val other=requireNotNull(factory.target(b.file))
        assertSame(first,routed.identity.runtime)
        assertSame(second,other.identity.runtime)
        assertNotNull(routed.identity.unavailable)
        assertNull(other.identity.unavailable)
        assertNotSame(routed.identity.session,other.identity.session)
        assertFalse(factory.isCurrent(old))
        assertTrue(a.verifyGrammar("SELECT 42") is VerifyResult.Unavailable)
        assertEquals(VerifyResult.Accepted,b.verifyGrammar("SELECT 42"))
        assertTrue(first.routed)
    }

    fun testFollowConsoleDoesNotContaminateOtherConsoleOrManagedProfile() {
        val a = console("grammar-follow-a")
        val b = console("grammar-follow-b")
        a.run("SELECT 1")
        b.run("SELECT 1")
        val first = a.grammarRuntime()
        val second = b.grammarRuntime()
        assertNotSame(first, second)
        val runtime = project.service<DuckdbGrammarRuntime>()
        runtime.follow(a.session, true)
        assertTrue(a.verifyGrammar("FROBULATE mango") is VerifyResult.Unavailable)
        assertEquals(VerifyResult.Accepted, b.verifyGrammar("SELECT 1"))
        assertEquals(emptyList<String>(), project.service<DuckdbGrammarSettings>().profile(target.uniqueId).enabled)
        a.session.setAutoCommit(false)
        a.run("SELECT 1")
        val snapshot = first.snapshot
        runtime.refresh(project, a.session)
        assertSame("Discovery must not issue a request in the user's transaction", snapshot, first.snapshot)
        a.run("ROLLBACK")
        runtime.follow(a.session, false)
    }

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
