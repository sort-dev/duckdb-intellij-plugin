package dev.sort.duckdb.pipes

import com.intellij.openapi.components.service
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.postgres.PgDialect
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.EditorPopupContract
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.duckdb.sql.DuckdbSqlDialect

class DuckdbEditorPopupTest : BasePlatformTestCase() {
    private val settings get() = project.service<DuckdbPipesSettings>()
    private lateinit var errors: BackgroundErrors
    private var previousEnabled = false
    private var previousVeto: String? = null

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        previousEnabled = settings.enabled
        previousVeto = System.getProperty(DuckdbPipeDialect.vetoProperty)
        System.clearProperty(DuckdbPipeDialect.vetoProperty)
        settings.enabled = true
    }

    override fun tearDown() {
        val recordedErrors = errors
        RunAll(
            {
                settings.enabled = previousEnabled
                previousVeto.let { value ->
                    if (value == null) System.clearProperty(DuckdbPipeDialect.vetoProperty)
                    else System.setProperty(DuckdbPipeDialect.vetoProperty, value)
                }
            },
            { super.tearDown() },
            { recordedErrors.assertNone() },
        ).run()
    }

    private fun contract() = EditorPopupContract(myFixture, "DuckdbPipes.EditorMenu",
        listOf("DuckdbPipes.PreviewSql", "DuckdbPipes.RunToStage"))

    private fun openSql(sql: String, dialect: SqlLanguageDialect = DuckdbSqlDialect) {
        val file = myFixture.configureByText("editor-popup.sql", sql).virtualFile
        setSqlDialectMapping(project, file, dialect, testRootDisposable)
        myFixture.configureFromExistingVirtualFile(file)
    }

    private fun openValidPipeContext() = openSql("SELECT 1;\nFROM range(3) |> LIMIT <caret>1")

    fun testEditorPopupContractIncludesUnrelatedKotlin() = contract().check(
        unrelated = mapOf(
            "ordinary.kt" to "fun ordinary() = \"FROM sample_rows |> LIMIT 1\"",
            "notes.txt" to "FROM sample_rows |> LIMIT 1",
        ),
    ) { openValidPipeContext() }

    fun testRequiresOwnPipeAtCaretAndEnabledSetting() {
        val popup = contract()
        openValidPipeContext()
        popup.assertShown()
        myFixture.editor.caretModel.moveToOffset(1)
        popup.assertAbsent(what = "ordinary SQL before the PIPE statement")
        openSql("SELECT <caret>1")
        popup.assertAbsent(what = "own dialect without PIPE")
        openSql("FROM sample_rows |> LIMIT <caret>1", PgDialect.INSTANCE)
        popup.assertAbsent(what = "another SQL dialect")
        openValidPipeContext()
        settings.enabled = false
        popup.assertAbsent(what = "PIPE disabled")
        settings.enabled = true
        popup.assertShown()
    }
}
