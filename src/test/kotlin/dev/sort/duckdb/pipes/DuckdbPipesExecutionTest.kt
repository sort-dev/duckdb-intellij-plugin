package dev.sort.duckdb.pipes

import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.datagrid.*
import com.intellij.database.settings.DatabaseSettings
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.sql.DuckdbSqlDialect
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.brikk.house.sql.intellij.pipe.lower
import java.util.UUID

/** Real console and stock preparation, recorded at the shared submission boundary. */
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
        for (sql in listOf("FROM offline_rows SELECT id |> WHERE id > 0",
            "SELECT \$\$literal; |> marker\$\$ AS note |> LIMIT 1")) Fixture(sql).use { f ->
            for (variant in 1..4) {
                f.editor.selectionModel.setSelection(0, sql.length)
                f.execute(variant)
                assertEquals(variant, f.requests.size)
                val request = f.requests.last() as DataRequest.QueryRequest
                assertEquals((DuckdbPipeDialect.lower(sql) as PipeTranslation.Lowered).sql, request.query)
                assertSame(f.console, request.owner)
                assertSame(f.editor, (request as DataRequest.CoupledWithEditor).editor)
                assertEquals(sql, f.editor.document.text)
                assertEmpty(f.previousEvents)
                assertEmpty(f.notifications)
            }
        }
    }
    fun testOrdinaryFromFirstAndQuotedMarkersReachPredecessor() {
        for (sql in listOf("FROM range(3)", "SELECT '|>'", "SELECT $$|>$$", "SELECT /* |> */ 1", "SELECT 'unclosed |> fake")) {
            Fixture(sql).use { f ->
                val event = f.execute()
                assertEquals(listOf(event), f.previousEvents)
                assertEmpty(f.requests)
            }
        }
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

    fun testNativeValidationRunsOnlyAfterParameterSubstitution() {
        val checked = mutableListOf<String>()
        var lookups = 0
        val dialect = object : PipeDialect by DuckdbPipeDialect {
            override val validator get() = object : NativeSqlValidator {
                override val timing = ValidationTiming.Submission
                private val native = DuckdbPipeDialect.validator
                override fun validate(sql: String, file: com.intellij.psi.PsiFile?): NativeVerdict {
                    assertNotNull(file)
                    assertFalse(sql, ":id" in sql || "|>" in sql)
                    checked.add(sql)
                    val source = requireNotNull(file)
                    val tracked = java.lang.reflect.Proxy.newProxyInstance(com.intellij.psi.PsiFile::class.java.classLoader,
                        arrayOf(com.intellij.psi.PsiFile::class.java)) { _, method, _ ->
                        if (method.name != "getProject") throw AssertionError("Unexpected file access: ${method.name}")
                        lookups++
                        source.project
                    } as com.intellij.psi.PsiFile
                    return native.validate(sql, tracked)
                }
            }
        }
        Fixture("FROM offline_rows |> WHERE id = :id |> SELECT id; FROM offline_rows |> LIMIT 2;", dialect).use { f ->
            ShowSqlParametersPanelAction.getStorage(f.console).putValue("id", "17")
            f.editor.caretModel.moveToOffset(f.editor.document.text.indexOf("WHERE"))
            withOption(DatabaseSettings.ExecOption().apply { execInside = DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT }) { f.execute() }
            assertEquals(1, f.requests.size)
            assertEquals(2, checked.size)
            assertEquals(1, lookups)
            val query = (f.requests.single() as DataRequest.QueryRequest).query
            assertTrue(query, "17" in query)
            (f.requests.single() as ConsoleDataRequest).onFinished()
            assertEquals(checked, f.requests.map { (it as DataRequest.QueryRequest).query })
            assertEmpty(f.previousEvents)
            assertEmpty(f.notifications)
        }
    }

    fun testSubmissionFailureDoesNotRetryThroughPredecessor() {
        Fixture("FROM offline_rows |> SELECT id").use { f ->
            val failure = IllegalStateException("recording producer failed")
            f.recording.onSubmit = { throw failure }
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

    private inner class Fixture(sql: String, private val dialect: PipeDialect = DuckdbPipeDialect) : AutoCloseable {
        private val id = UUID.randomUUID().toString()
        private val vf = myFixture.configureByText("offline-pipe-$id.sql", sql).virtualFile
        val file get() = PsiManager.getInstance(project).findFile(vf)!!
        val editor = myFixture.editor
        val recording: RecordingConsole
        val requests get() = recording.requests
        val console get() = recording.console
        val previousEvents = mutableListOf<AnActionEvent>()
        val notifications = mutableListOf<Notification>()
        private val notificationConnection = project.messageBus.connect()
        init {
            dev.brikk.house.sql.intellij.testing.setSqlDialectMapping(project, vf, DuckdbSqlDialect)
            recording = RecordingConsole(project, vf, "duckdb-brikk-native")
            notificationConnection.subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "DuckDB PIPE") {
                        assertEquals(DuckdbPipeDialect.label, notification.title)
                        assertTrue(notification.content.isNotBlank())
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
                PipeExecuteInterceptor(dialect), variant == 4)
            action.actionPerformed(event)
            return event
        }
        override fun close() {
            notificationConnection.disconnect()
            notifications.forEach { it.expire() }
            try {
                recording.close()
            } finally {
                dev.brikk.house.sql.intellij.testing.setSqlDialectMapping(project, vf, null)
            }
        }
    }
}
