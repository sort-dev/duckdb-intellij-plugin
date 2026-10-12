package dev.sort.duckdb.pipes

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.PipeExecutionContract
import dev.sort.duckdb.setSqlDialectMapping
import dev.sort.duckdb.sql.DuckdbSqlDialect
import java.util.UUID

class DuckdbPipeExecutionContractTest : BasePlatformTestCase() {
    private val settings get() = project.service<DuckdbPipesSettings>()
    private var previousFlag: String? = null
    private var previousEnabled = false
    private val mappedFiles = mutableListOf<VirtualFile>()

    override fun setUp() {
        super.setUp()
        previousFlag = System.getProperty(DuckdbPipeDialect.vetoProperty)
        previousEnabled = settings.enabled
        System.clearProperty(DuckdbPipeDialect.vetoProperty)
        settings.enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            mappedFiles.forEach { setSqlDialectMapping(project, it, null) }
            mappedFiles.clear()
            settings.enabled = previousEnabled
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            previousFlag.let { flag ->
                if (flag == null) System.clearProperty(DuckdbPipeDialect.vetoProperty)
                else System.setProperty(DuckdbPipeDialect.vetoProperty, flag)
            }
        } finally {
            super.tearDown()
        }
    }

    private fun contract() = PipeExecutionContract(DuckdbPipeDialect, "duckdb-brikk-native", "mysql", { settings.enabled = it }) { sql ->
        val file = myFixture.configureByText("pipe-contract-${UUID.randomUUID()}.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, DuckdbSqlDialect.INSTANCE)
        mappedFiles.add(file.virtualFile)
        myFixture.editor
    }

    fun testDeclinesWhenNotOwned() = contract().declinesWhenNotOwned()
    fun testSelectsStatementsLikeStockExecute() = contract().selectsStatementsLikeStockExecute()
    fun testRefusesWholeScriptsItCannotRun() = contract().refusesWholeScriptsItCannotRun()
    fun testClaimsOnlyPipes() = contract().claimsOnlyPipes()
    fun testRefusesStaleInput() = contract().refusesStaleInput()
    fun testPropagatesCancellation() = contract().propagatesCancellation()
    fun testLowersNestedOnlyPipelines() = contract().lowersNestedOnlyPipelines()
    fun testRunsTopLevelStagesToTheCaret() = contract().runsTopLevelStagesToTheCaret()
    fun testRefusesDestinationChangesDuringPrompting() = contract().refusesDestinationChangesDuringPrompting()
    fun testNotifiesRefusals() = contract().notifiesRefusals()
}
