package dev.sort.duckdb.pipes

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStatement
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.sql.DuckdbSqlDialect
import dev.sort.duckdb.validate.DuckdbErrorAnnotator
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.ExecuteInterceptor
import dev.brikk.house.sql.intellij.ExecutionClaim
import dev.brikk.house.sql.intellij.pipe.isEnabled

class DuckdbPipesPlatformTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { project.service<DuckdbPipesSettings>().enabled = false
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally { super.tearDown() }
    }
    fun testSettingDefaultsOffAndDoesNotReplaceActions() {
        val manager = ActionManager.getInstance()
        val actions = DuckdbPipesActionConfiguration.EXECUTE_IDS.map { manager.getAction(it) }
        assertFalse(DuckdbPipeDialect.isEnabled(project))
        project.service<DuckdbPipesSettings>().enabled = true
        assertTrue(DuckdbPipeDialect.isEnabled(project))
        project.service<DuckdbPipesSettings>().enabled = false
        assertEquals(actions, DuckdbPipesActionConfiguration.EXECUTE_IDS.map { manager.getAction(it) })
    }
    fun testPipelineIsOneStatementEvenWithLongSelectHead() {
        project.service<DuckdbPipesSettings>().enabled = true
        val longHead = (1..600).joinToString(", ") { "$it AS c$it" }
        val sql = "SELECT $longHead |> LIMIT 1; SELECT 2;"
        val file = PsiFileFactory.getInstance(project).createFileFromText("pipe.sql", DuckdbSqlDialect.INSTANCE, sql)
        val statements = PsiTreeUtil.findChildrenOfType(file, SqlStatement::class.java).filterNot { it.parent is SqlStatement }
        assertEquals(2, statements.size)
        assertTrue(statements.first().text.contains("|> LIMIT 1"))
    }
    fun testPipeDiagnosticsReplaceNativeValidatorErrors() {
        project.service<DuckdbPipesSettings>().enabled = true
        val file = PsiFileFactory.getInstance(project).createFileFromText("pipe.sql", DuckdbSqlDialect.INSTANCE,
            "FROM range(3) |> LIMIT 1; FROM t |> BOGUS 2; SELECT FROM WHERE;")
        val annotator = DuckdbErrorAnnotator()
        val info = annotator.collectInformation(file)!!
        assertEquals(2, info.pipes.size)
        assertEquals(1, info.statements.size)
        val result = annotator.doAnnotate(info)!!
        assertEquals(1, result.pipeErrors.size)
        assertEquals(1, result.errors.size)
    }
    fun testCapturedActionsDelegateExactlyOnceAndClaimedFailuresNeverDelegate() {
        var calls = 0
        val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { calls++ } }
        val event = AnActionEvent.createFromDataContext("PIPE test", Presentation(), DataContext.EMPTY_CONTEXT)
        for (variant in 1..4) {
            val action = CooperativeExecuteAction(if (variant == 4) -1 else variant, previous,
                ExecuteInterceptor { _, _ -> ExecutionClaim.DECLINED }, variant == 4)
            action.actionPerformed(event)
            assertSame(previous, action.delegate)
        }
        assertEquals(4, calls)
        val claimed = CooperativeExecuteAction(1, previous, ExecuteInterceptor { _, _ -> throw ProcessCanceledException() })
        try { claimed.actionPerformed(event); fail("cancellation must propagate") } catch (_: ProcessCanceledException) { }
        assertEquals(4, calls)
        claimed.detach()
        claimed.actionPerformed(event)
        assertEquals(5, calls)
    }
    fun testDynamicRegistrationRestoresCapturedPredecessors() {
        val manager = ActionManager.getInstance()
        val before = DuckdbPipesActionConfiguration.EXECUTE_IDS.map { manager.getAction(it) }
        val configuration = DuckdbPipesActionConfiguration()
        try {
            configuration.registerActions(manager)
            configuration.registerActions(manager)
            DuckdbPipesActionConfiguration.EXECUTE_IDS.forEachIndexed { index, id ->
                assertSame(before[index], (manager.getAction(id) as CooperativeExecuteAction).delegate)
            }
        } finally { configuration.unregisterActions(manager) }
        assertEquals(before, DuckdbPipesActionConfiguration.EXECUTE_IDS.map { manager.getAction(it) })
    }

    fun testVariantsReadLiveSettingsAndSelectionUsesFixedDefaults() {
        val settings = com.intellij.database.settings.DatabaseSettings.getSettings()
        val saved = settings.execOptions.toList()
        val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) {} }
        val event = AnActionEvent.createFromDataContext("Execute options", Presentation(), DataContext.EMPTY_CONTEXT)
        var observed: com.intellij.database.settings.DatabaseSettings.ExecOption? = null
        val interceptor = ExecuteInterceptor { _, option -> observed = option; ExecutionClaim.HANDLED }
        try {
            val actions = (1..3).map { CooperativeExecuteAction(it, previous, interceptor) }
            val selection = CooperativeExecuteAction(-1, previous, interceptor, true)
            for (i in 0..2) {
                val option = com.intellij.database.settings.DatabaseSettings.ExecOption().apply { newTab = true }
                settings.execOptions[i] = option
                actions[i].actionPerformed(event)
                assertSame(option, observed)
            }
            selection.actionPerformed(event)
            assertFalse(observed!!.newTab)
            assertEquals(1, observed!!.execSelection)
        } finally { saved.forEachIndexed { index, option -> settings.execOptions[index] = option } }
    }
    fun testStageKeywordsAndInputAliasesCompleteWithoutACatalog() {
        project.service<DuckdbPipesSettings>().enabled = true
        dev.sort.duckdb.setSqlDialectMapping(project, null, DuckdbSqlDialect.INSTANCE)
        myFixture.configureByText("keywords.sql", "FROM range(3) |> <caret>")
        myFixture.completeBasic()
        assertTrue("Lookups: ${myFixture.lookupElementStrings}",
            myFixture.lookupElementStrings.orEmpty().any { it.equals("WHERE", true) })
        assertFalse(myFixture.lookupElementStrings.orEmpty().contains("ABORT"))
        com.intellij.codeInsight.lookup.LookupManager.getInstance(project).hideActiveLookup()
        myFixture.configureByText("columns.sql", "FROM range(3) |> SELECT range AS my_output |> WHERE my_ou<caret>")
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("my_output") ||
            myFixture.editor.document.text.contains("WHERE my_output"))
    }
    fun testWholeStagePhraseGetsKeywordColoring() {
        project.service<DuckdbPipesSettings>().enabled = true
        val sql = "FROM t |> FULL OUTER JOIN u ON t.id = u.id"
        val file = myFixture.configureByText("stage-colors.sql", sql)
        dev.sort.duckdb.setSqlDialectMapping(project, file.virtualFile, DuckdbSqlDialect.INSTANCE)
        val colors = myFixture.doHighlighting().filter {
            it.forcedTextAttributesKey == com.intellij.openapi.editor.DefaultLanguageHighlighterColors.KEYWORD
        }
        for (word in listOf("FULL", "OUTER", "JOIN")) {
            val start = sql.indexOf(word)
            assertTrue("Missing keyword color for $word: $colors", colors.any {
                it.startOffset <= start && it.endOffset >= start + word.length
            })
        }
    }
    fun testBothPeerWrapperOrdersPreserveOwnershipAndOrdinaryExecution() {
        val event = AnActionEvent.createFromDataContext("PIPE chain test", Presentation(), DataContext.EMPTY_CONTEXT)
        for (variant in 1..4) for (duckdbFirst in listOf(false, true)) {
            var calls = 0
            var visits = 0
            val terminal = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { calls++ } }
            fun duckdb(previous: AnAction) = CooperativeExecuteAction(if (variant == 4) -1 else variant, previous,
                ExecuteInterceptor { _, _ -> visits++; ExecutionClaim.DECLINED }, variant == 4)
            fun peer(previous: AnAction) = object : AnAction(), ActionWithDelegate<AnAction> {
                override fun getDelegate() = previous
                override fun actionPerformed(e: AnActionEvent) = ActionWrapperUtil.actionPerformed(e, this, previous)
            }
            val action = if (duckdbFirst) peer(duckdb(terminal)) else duckdb(peer(terminal))
            action.actionPerformed(event)
            assertEquals(1, calls)
            assertEquals(1, visits)
        }
    }
}
