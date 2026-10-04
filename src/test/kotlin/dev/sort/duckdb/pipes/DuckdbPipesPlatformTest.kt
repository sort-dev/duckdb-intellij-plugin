package dev.sort.duckdb.pipes

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlStatement
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.sql.DuckdbSqlDialect
import dev.sort.duckdb.validate.DuckdbErrorAnnotator

class DuckdbPipesPlatformTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { project.service<DuckdbPipesSettings>().enabled = false
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally { super.tearDown() }
    }
    fun testSettingDefaultsOffAndDoesNotReplaceActions() {
        val manager = ActionManager.getInstance()
        val actions = DuckdbPipesActionConfiguration.EXECUTE_IDS.map { manager.getAction(it) }
        assertFalse(DuckdbPipes.isEnabled(project))
        project.service<DuckdbPipesSettings>().enabled = true
        assertTrue(DuckdbPipes.isEnabled(project))
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
            val action = if (variant == 4) DuckdbPipesRunSelectionAction(previous) { _, _ -> false }
                else DuckdbPipesRunQueryAction(variant, previous) { _, _ -> false }
            action.actionPerformed(event)
            assertSame(previous, action.delegate)
        }
        assertEquals(4, calls)
        val claimed = DuckdbPipesRunQueryAction(1, previous) { _, _ -> throw ProcessCanceledException() }
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
                assertSame(before[index], (manager.getAction(id) as DuckdbPipesRunQueryAction).delegate)
            }
        } finally { configuration.unregisterActions(manager) }
        assertEquals(before, DuckdbPipesActionConfiguration.EXECUTE_IDS.map { manager.getAction(it) })
    }
    fun testStageKeywordsAndInputAliasesCompleteWithoutACatalog() {
        project.service<DuckdbPipesSettings>().enabled = true
        SqlDialectMappings.getInstance(project).setMapping(null, DuckdbSqlDialect.INSTANCE)
        myFixture.configureByText("keywords.sql", "FROM range(3) |> <caret>")
        myFixture.completeBasic()
        assertTrue("Lookups: ${myFixture.lookupElementStrings}",
            myFixture.lookupElementStrings.orEmpty().any { it.equals("WHERE", true) })
        com.intellij.codeInsight.lookup.LookupManager.getInstance(project).hideActiveLookup()
        myFixture.configureByText("columns.sql", "FROM range(3) |> SELECT range AS my_output |> WHERE my_ou<caret>")
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("my_output") ||
            myFixture.editor.document.text.contains("WHERE my_output"))
    }
    fun testBothPeerWrapperOrdersPreserveOwnershipAndOrdinaryExecution() {
        val event = AnActionEvent.createFromDataContext("PIPE chain test", Presentation(), DataContext.EMPTY_CONTEXT)
        for (variant in 1..4) for (duckdbFirst in listOf(false, true)) {
            var calls = 0
            var visits = 0
            val terminal = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { calls++ } }
            fun duckdb(previous: AnAction) = if (variant == 4) DuckdbPipesRunSelectionAction(previous) { _, _ -> visits++; false }
                else DuckdbPipesRunQueryAction(variant, previous) { _, _ -> visits++; false }
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
