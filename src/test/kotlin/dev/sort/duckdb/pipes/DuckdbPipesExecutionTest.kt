package dev.sort.duckdb.pipes

import com.intellij.database.DataBus
import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.client.DatabaseSessionClient
import com.intellij.database.console.client.DatabaseSessionClientWithFile
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.datagrid.*
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.settings.DatabaseSettings
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.lang.reflect.Proxy
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.ExecuteInterceptor
import dev.brikk.house.sql.intellij.TranslatingScriptModel
import java.util.UUID

/** Real console and stock preparation; only the session and request producer are offline doubles. */
class DuckdbPipesExecutionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.service<DuckdbPipesSettings>().enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
    override fun tearDown() {
        try {
            project.service<DuckdbPipesSettings>().enabled = false
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally { super.tearDown() }
    }
    fun testAllExecuteVariantsUseGeneratedSqlAndOriginalEditorRange() {
        val sql = "FROM offline_rows |> SELECT id |> WHERE id > 0"
        Fixture(sql).use { f ->
            for (variant in 1..4) {
                f.editor.selectionModel.setSelection(0, sql.length)
                f.execute(variant)
                assertEquals(variant, f.requests.size)
                val request = f.requests.last() as DataRequest.QueryRequest
                assertEquals((DuckdbPipesEngine.transpile(sql) as DuckdbPipesEngine.Transpile.Ok).sql, request.query)
                assertSame(f.console, request.owner)
                assertSame(f.editor, (request as DataRequest.CoupledWithEditor).editor)
                assertEquals(sql, f.editor.document.text)
                assertEmpty(f.previousEvents)
                assertEmpty(f.notifications)
            }
        }
    }
    fun testUnselectedPipelineUsesPlatformStatementPreparation() {
        Fixture("SELECT 1; FROM offline_rows |> LIMIT 2; SELECT 9;").use { f ->
            f.editor.caretModel.moveToOffset(f.editor.document.text.indexOf("LIMIT"))
            val settings = DatabaseSettings.getSettings()
            val previous = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply { execInside = DatabaseSettings.EXECUTE_INSIDE_SMALLEST }
            try { f.execute() } finally { settings.execOptions[0] = previous }
            assertEquals("Notifications: ${f.notifications.map { it.content }}; predecessor calls: ${f.previousEvents.size}", 1, f.requests.size)
            assertFalse((f.requests.single() as DataRequest.QueryRequest).query.contains("|>"))
            assertEmpty(f.previousEvents)
        }
    }
    fun testOrdinaryFromFirstAndQuotedMarkersReachPredecessor() {
        for (sql in listOf("FROM range(3)", "SELECT '|>'", "SELECT $$|>$$", "SELECT /* |> */ 1")) {
            Fixture(sql).use { f ->
                val event = f.execute()
                assertEquals(listOf(event), f.previousEvents)
                assertEmpty(f.requests)
            }
        }
    }
    fun testBrokenSelectedPipeNeverExecutesEitherPipeOrOrdinaryNeighbor() {
        val settings = DatabaseSettings.getSettings()
        val previous = settings.execOptions[0]
        try {
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execSelection = DatabaseSettings.EXECUTE_SELECTION_EXACTLY_SCRIPT
            }
            Fixture("SELECT 1; FROM t |> BOGUS 2;").use { f ->
                f.editor.selectionModel.setSelection(0, f.editor.document.textLength)
                f.execute()
                assertEmpty(f.requests)
                assertEmpty(f.previousEvents)
                assertEquals(1, f.notifications.size)
            }
        } finally { settings.execOptions[0] = previous }
    }
    fun testMixedScriptParametersAndNewTabsUseTheStockRequestChain() {
        val sql = "SELECT 1; FROM offline_rows |> WHERE id = :id |> SELECT id; SELECT 2;"
        Fixture(sql).use { f ->
            f.editor.caretModel.moveToOffset(sql.indexOf("WHERE"))
            ShowSqlParametersPanelAction.getStorage(f.console).putValue("id", "17")
            val settings = DatabaseSettings.getSettings()
            val previous = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT
                newTab = true
            }
            try {
                f.execute()
                assertEquals(1, f.requests.size)
                repeat(2) { (f.requests.last() as ConsoleDataRequest).onFinished() }
            } finally { settings.execOptions[0] = previous }
            val requests = f.requests.map { it as ConsoleDataRequest }
            assertEquals(3, requests.size)
            assertEquals("SELECT 1", requests.first().query.trim())
            assertEquals("SELECT 2", requests.last().query.trim())
            assertTrue(requests[1].query, requests[1].query.contains("17"))
            assertFalse(requests[1].query.contains(":id") || requests[1].query.contains("|>"))
            assertTrue(requests.all { it.newTab && it.owner === f.console })
            assertEmpty(f.previousEvents)
            assertEmpty(f.notifications)
        }
    }
    fun testRunToFirstStageOfClaimedPipeline() {
        Fixture("FROM offline_rows |> WHERE id > 1 |> LIMIT 2").use { f ->
            f.editor.caretModel.moveToOffset(3)
            DuckdbPipesUi.runToStage(project, f.file, f.editor)
            assertEquals(1, f.requests.size)
            val query = (f.requests.single() as DataRequest.QueryRequest).query
            assertFalse(query.contains("|>"))
            assertFalse(query.contains("WHERE"))
            assertEmpty(f.previousEvents)
        }
    }

    fun testOutsideStatementWholeScriptStillClaimsPipelines() {
        Fixture("SELECT 1; FROM offline_rows |> SELECT id; -- outside statements").use { f ->
            f.editor.caretModel.moveToOffset(f.editor.document.textLength)
            withOption(DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_SMALLEST
                execOutside = DatabaseSettings.EXECUTE_OUTSIDE_WHOLE_SCRIPT
            }) {
                f.execute()
                assertEquals(f.notifications.map { it.content }.toString(), 1, f.requests.size)
                (f.requests.single() as ConsoleDataRequest).onFinished()
            }
            assertEquals(2, f.requests.size)
            assertFalse((f.requests.last() as DataRequest.QueryRequest).query.contains("|>"))
            assertEmpty(f.previousEvents)
        }
    }

    fun testOutsideTailPreservesLeadingCommentBoundary() {
        Fixture("SELECT 0; /* ) caret here */ FROM offline_rows |> SELECT id;").use { f ->
            f.editor.caretModel.moveToOffset(f.editor.document.text.indexOf("caret"))
            withOption(DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_SMALLEST
                execOutside = DatabaseSettings.EXECUTE_OUTSIDE_SCRIPT_TAIL
            }) { f.execute() }
            assertEquals(f.notifications.map { it.content }.toString(), 1, f.requests.size)
            assertFalse((f.requests.single() as DataRequest.QueryRequest).query.contains("|>"))
            assertEmpty(f.previousEvents)
        }
    }

    fun testSmartSelectionClaimsTheEnclosingPipeline() {
        Fixture("FROM offline_rows |> WHERE id > 0 |> SELECT id").use { f ->
            val text = f.editor.document.text
            f.editor.selectionModel.setSelection(text.indexOf("WHERE"), text.indexOf(" |> SELECT"))
            withOption(DatabaseSettings.ExecOption().apply {
                execSelection = DatabaseSettings.EXECUTE_SELECTION_SMART_EXPAND
            }) { f.execute() }
            assertEquals(f.notifications.map { it.content }.toString(), 1, f.requests.size)
            assertFalse((f.requests.single() as DataRequest.QueryRequest).query.contains("|>"))
            assertEmpty(f.previousEvents)
        }
    }

    fun testNamedLimitIsSubstitutedBeforeLowering() {
        Fixture("FROM offline_rows |> LIMIT :rows").use { f ->
            ShowSqlParametersPanelAction.getStorage(f.console).putValue("rows", "3")
            f.execute()
            assertEquals(f.notifications.map { it.content }.toString(), 1, f.requests.size)
            val sql = (f.requests.single() as DataRequest.QueryRequest).query
            assertTrue(sql, sql.contains("LIMIT 3"))
            assertFalse(sql.contains(":rows") || sql.contains("|>"))
            assertEmpty(f.previousEvents)
        }
    }

    fun testConfiguredParametersInLiteralsAndLoweringSensitivePositions() {
        val settings = DatabaseSettings.getSettings()
        val saved = settings.userPatterns
        settings.userPatterns = com.intellij.database.settings.UserPatterns().apply {
            inScripts = true; inLiterals = true; processStrings = true
            patterns = mutableListOf(com.intellij.database.settings.UserPatterns.ParameterPattern(
                "\\$\\{([^\\{\\}]*)\\}", "", "\${name}").enabled(true, true))
        }
        settings.patternCache.clear()
        try {
            Fixture("FROM offline_rows |> WHERE id = \${id} AND status = '\${status}' |> LIMIT \${rows}").use { f ->
                val storage = ShowSqlParametersPanelAction.getStorage(f.console)
                storage.putValue("id", "17"); storage.putValue("status", "open"); storage.putValue("rows", "3")
                f.editor.selectionModel.setSelection(0, f.editor.document.textLength)
                f.execute()
                assertEquals(f.notifications.map { it.content }.toString(), 1, f.requests.size)
                val sql = (f.requests.single() as DataRequest.QueryRequest).query
                assertTrue(sql, "17" in sql && "'open'" in sql && "LIMIT 3" in sql)
                assertFalse(sql, "\${" in sql || "|>" in sql)
                assertEmpty(f.previousEvents)
            }
        } finally { settings.userPatterns = saved; settings.patternCache.clear() }
    }

    fun testInvalidSubstitutedLimitBlocksTheWholeMixedScript() {
        Fixture("SELECT 1; FROM offline_rows |> LIMIT :rows;").use { f ->
            ShowSqlParametersPanelAction.getStorage(f.console).putValue("rows", "-1")
            withOption(DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT
            }) { f.execute() }
            assertEmpty(f.requests)
            assertEmpty(f.previousEvents)
            assertFalse(f.notifications.isEmpty())
        }
    }

    fun testStaleSnapshotRefusesEvenWhenTextIsRestored() {
        Fixture("FROM offline_rows |> SELECT id").use { f ->
            val model = TranslatingScriptModel(f.console.scriptModel, f.editor, DuckdbExecutionAdapter())
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                f.editor.document.insertString(0, " ")
                f.editor.document.deleteString(0, 1)
            }
            try { model.finalizeTranslations(f.console.pStorage); fail("Stale revision must refuse") }
            catch (_: IllegalStateException) { }
            assertFalse(model.isActual)
            assertEmpty(f.requests)
        }
    }

    fun testSourceMapIsUnavailableAfterParameterExpansion() {
        for (parameterized in listOf(false, true)) Fixture(
            if (parameterized) "FROM offline_rows |> WHERE id = :id" else "FROM offline_rows |> SELECT id").use { f ->
            ShowSqlParametersPanelAction.getStorage(f.console).putValue("id", "17")
            val model = TranslatingScriptModel(f.console.scriptModel, f.editor, DuckdbExecutionAdapter())
            model.finalizeTranslations(f.console.pStorage)
            val statement = model.statements().single()!!
            val map = model.authoredMap(statement.range(), statement.rangeOffset())
            if (parameterized) assertNull(map) else assertNotNull(map)
            assertEmpty(f.requests)
        }
    }

    fun testMissingSubmissionClientDoesNotDelegateRawPipe() {
        Fixture("FROM offline_rows |> SELECT id").use { f ->
            f.hasSubmissionClient = false
            f.execute()
            assertEmpty(f.requests)
            assertEmpty(f.previousEvents)
        }
    }

    fun testSubmissionFailureDoesNotRetryThroughPredecessor() {
        Fixture("FROM offline_rows |> SELECT id").use { f ->
            val failure = IllegalStateException("recording producer failed")
            f.submissionFailure = failure
            try { f.execute(); fail("Submission failure must propagate") }
            catch (e: IllegalStateException) { assertSame(failure, e) }
            assertEquals(1, f.requests.size)
            assertEmpty(f.previousEvents)
        }
    }

    private fun withOption(option: DatabaseSettings.ExecOption, action: () -> Unit) {
        val settings = DatabaseSettings.getSettings()
        val previous = settings.execOptions[0]
        try { settings.execOptions[0] = option; action() } finally { settings.execOptions[0] = previous }
    }

    private inner class Fixture(sql: String) : AutoCloseable {
        private val id = UUID.randomUUID().toString()
        private val vf = myFixture.configureByText("offline-pipe-$id.sql", sql).virtualFile
        val file get() = PsiManager.getInstance(project).findFile(vf)!!
        val editor = myFixture.editor
        val requests = mutableListOf<GridDataRequest>()
        val previousEvents = mutableListOf<AnActionEvent>()
        val notifications = mutableListOf<Notification>()
        var hasSubmissionClient = true
        var submissionFailure: RuntimeException? = null
        private val clients = mutableListOf<DatabaseSessionClient>()
        private val auditors = mutableListOf<DataAuditor>()
        private val userData = UserDataHolderBase()
        private val notificationConnection = project.messageBus.connect()
        private var sessionViewCreated = false
        private val state = Proxy.newProxyInstance(DatabaseSession.State::class.java.classLoader,
            arrayOf(DatabaseSession.State::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Offline session state"
                "isIdle", "isFinalized", "isEmpty" -> true
                "isCancelled" -> false
                "getStartTime", "getTimeSpentMs" -> 0L
                "getWork" -> emptyList<DatabaseSession.State.Work>()
                "getWorkFor", "getMostRecentWork" -> null
                else -> throw UnsupportedOperationException("State: $method")
            }
        } as DatabaseSession.State
        private val producer = object : DataProducer {
            override fun processRequest(request: GridDataRequest) {
                requests.add(request)
                submissionFailure?.let { throw it }
                auditors.forEach { it.jobSubmitted(request as DataRequest, this) }
            }
        }
        private val bus = object : DataBus.Consuming {
            override fun filterFor(owner: DataRequest.Owner): DataBus.Consuming = this
            override fun getDataProducer(): DataProducer = producer
            override fun addConsumer(consumer: DataConsumer) {}
            override fun addAuditor(auditor: DataAuditor) { auditors.add(auditor) }
        }
        private val point = LocalDataSource().apply {
            name = "Offline DuckDB PIPE $id"
            databaseDriver = DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native")!!
            url = "jdbc:offline:duckdb-pipes-test"
            isAutoSynchronize = false
            isKeepAlive = false
        }
        private val session = Proxy.newProxyInstance(DatabaseSession::class.java.classLoader,
            arrayOf(DatabaseSession::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString", "getTitle", "getDisplayName" -> "Offline PIPE session"
                "getConnectionPoint", "getTarget" -> point
                "getState" -> { sessionViewCreated = true; state }
                "getProject" -> project
                "getMessageBus" -> bus
                "getClients" -> clients.toTypedArray()
                "getClientsWithFile" -> if (hasSubmissionClient) clients.filterIsInstance<DatabaseSessionClientWithFile>().toTypedArray()
                    else emptyArray<DatabaseSessionClientWithFile>()
                "attach" -> { clients.add(args!![0] as DatabaseSessionClient); null }
                "detach" -> { clients.remove(args!![0] as DatabaseSessionClient); null }
                "isValid", "isIdle" -> true
                "isConnected", "isInternal", "isService", "isCancelled" -> false
                "getCurrentTx" -> DataRequest.AUTO_COMMIT
                "getUserData" -> { @Suppress("UNCHECKED_CAST") userData.getUserData(args!![0] as Key<Any>) }
                "putUserData" -> { @Suppress("UNCHECKED_CAST") userData.putUserData(args!![0] as Key<Any>, args[1]); null }
                else -> throw UnsupportedOperationException("Session: $method")
            }
        } as DatabaseSession
        val console: JdbcConsole
        init {
            dev.sort.duckdb.setSqlDialectMapping(project, vf, DuckdbSqlDialect.INSTANCE)
            LocalDataSourceManager.getInstance(project).addDataSource(point)
            DbPsiFacade.getInstance(project).clearCaches()
            console = JdbcConsole.newConsole(project).forFile(vf).fromDataSource(point).useSession(session).build()
            assertTrue(console.isValid)
            notificationConnection.subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "DuckDB PIPE") {
                        notification.setSuppressShowingPopup(true)
                        notifications.add(notification)
                    }
                }
            })
        }
        fun execute(variant: Int = 1): AnActionEvent {
            val event = AnActionEvent.createFromDataContext("DuckDB PIPE execution test", Presentation(),
                SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
                    .add(CommonDataKeys.PSI_FILE, file).add(CommonDataKeys.VIRTUAL_FILE, vf)
                    .add(SessionClientHolder.CLIENT_KEY, console).build())
            val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { previousEvents.add(e) } }
            val action = CooperativeExecuteAction(if (variant == 4) -1 else variant, previous,
                ExecuteInterceptor(PipesExecuteInterceptor::handle), variant == 4)
            action.actionPerformed(event)
            return event
        }
        override fun close() {
            notificationConnection.disconnect()
            notifications.forEach { it.expire() }
            try {
                if (sessionViewCreated) Disposer.dispose(console.consoleView)
                Disposer.dispose(console)
            } finally {
                LocalDataSourceManager.getInstance(project).removeDataSource(point)
                DbPsiFacade.getInstance(project).clearCaches()
                dev.sort.duckdb.setSqlDialectMapping(project, vf, null)
            }
        }
    }
}
