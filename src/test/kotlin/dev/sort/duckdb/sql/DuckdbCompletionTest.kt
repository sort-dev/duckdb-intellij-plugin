package dev.sort.duckdb.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.components.service
import com.intellij.testFramework.RunAll
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.sort.duckdb.pipes.DuckdbPipesSettings

class DuckdbCompletionTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private var autocomplete = false
    private var pipes = false

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        pipes = project.service<DuckdbPipesSettings>().enabled
        project.service<DuckdbPipesSettings>().enabled = true
        setSqlDialectMapping(project, null, DuckdbSqlDialect, testRootDisposable)
    }

    override fun tearDown() = RunAll(
        { CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete },
        { project.service<DuckdbPipesSettings>().enabled = pipes },
        { errors.assertNone() },
        { super.tearDown() },
    ).run()

    private fun functions(sql: String): List<FunctionPresentation> {
        myFixture.configureByText("positions.sql", sql)
        return myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
    }

    fun testFromRangeOffersOnlyTableFunctions() {
        val entries = functions("FROM ra<caret>")
        assertEquals(setOf(FunctionKind.TABLE_VALUED), entries.filter { it.name == "range" }.map { it.kind }.toSet())
        assertFalse(entries.toString(), entries.any { it.name == "random" || it.kind == FunctionKind.SCALAR })
    }

    fun testSelectRangeOffersOnlyScalarFunctions() {
        val entries = functions("SELECT ra<caret>")
        for (name in listOf("range", "random")) {
            assertEquals(name, setOf(FunctionKind.SCALAR), entries.filter { it.name == name }.map { it.kind }.toSet())
        }
        assertFalse(entries.toString(), entries.any { it.kind in setOf(FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING) })
    }

    fun testUnnestInsertsLowercaseInExpressionAndOffersScalarArguments() {
        myFixture.configureByText("unnest.sql", "SELECT unn<caret>")
        val item = myFixture.completeBasic().orEmpty().single { (it.`object` as? FunctionPresentation)?.name == "unnest" }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup('\n')
        myFixture.checkResult("SELECT unnest(<caret>)")
        val entries = functions("SELECT unnest(<caret>)")
        assertTrue(entries.toString(), entries.any { it.name == "abs" && it.kind == FunctionKind.SCALAR })
        assertFalse(entries.toString(), entries.any { it.name == "range" && it.kind == FunctionKind.TABLE_VALUED })
    }

    fun testExtractFromIsAnExpressionPosition() {
        val entries = functions("SELECT EXTRACT(YEAR FROM <caret>)")
        assertTrue(entries.toString(), entries.any { it.name == "abs" && it.kind == FunctionKind.SCALAR })
        assertFalse(entries.toString(), entries.any { it.kind in setOf(FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING) && it.name != "unnest" })
    }

    fun testPipeStageHeadUsesSharedPresentationAndNoFunctions() {
        myFixture.configureByText("stages.sql", "FROM range(2) |> <caret>")
        val items = myFixture.completeBasic().orEmpty()
        assertFalse(items.toString(), items.any { it.`object` is FunctionPresentation })
        val stages = items.associateBy { it.lookupString }
        for (phrase in listOf("SELECT", "CALL", "DROP", "SET", "RENAME", "PIVOT", "UNPIVOT", "TABLESAMPLE", "ASOF JOIN", "NATURAL JOIN", "POSITIONAL JOIN")) {
            val item = requireNotNull(stages[phrase]) { "Missing $phrase: ${stages.keys}" }
            val shown = LookupElementPresentation().also(item::renderElement)
            assertEquals(phrase, shown.itemText)
            assertEquals(phrase, "PIPE stage", shown.typeText)
            assertTrue(phrase, shown.isItemTextBold)
        }
        myFixture.lookup.currentItem = stages.getValue("SELECT")
        myFixture.finishLookup('\n')
        myFixture.checkResult("FROM range(2) |> SELECT<caret>")
        myFixture.configureByText("stage-body.sql", "FROM range(2) |> WHERE ra<caret>")
        assertFalse(myFixture.completeBasic().orEmpty().any { LookupElementPresentation().also(it::renderElement).typeText == "PIPE stage" })
    }

    fun testDuckdbFunctionsOfferedInExpression() {
        // mapping must precede file creation — a post-configure setMapping leaves the completion
        // session on the pre-mapping PSI (empirically bisected; highlight-only tests tolerate it)
        setSqlDialectMapping(project, null, DuckdbSqlDialect)
        myFixture.configureByText("c.sql", "SELECT list_tra<caret> FROM t;")
        myFixture.completeBasic()
        // a single match auto-inserts (lookup never shows) — accept either evidence
        val lookups = myFixture.lookupElementStrings.orEmpty()
        val text = myFixture.editor.document.text
        assertTrue(
            "expected list_transform offered or inserted; lookups=${lookups.take(12)} text=$text",
            lookups.contains("list_transform") || text.contains("list_transform("),
        )
    }

    fun testTableFunctionOfferedInFrom() {
        setSqlDialectMapping(project, null, DuckdbSqlDialect)
        myFixture.configureByText("f.sql", "SELECT * FROM read_par<caret>;")
        myFixture.completeBasic()
        val lookups = myFixture.lookupElementStrings.orEmpty()
        val text = myFixture.editor.document.text
        assertTrue(
            "expected read_parquet offered or inserted; lookups=${lookups.take(12)} text=$text",
            lookups.contains("read_parquet") || text.contains("read_parquet("),
        )
    }
}
