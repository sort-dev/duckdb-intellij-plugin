package dev.sort.duckdb.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping

class DuckdbCompletionInsertionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        setSqlDialectMapping(project, null, DuckdbSqlDialect)
    }

    override fun tearDown() {
        try {
            setSqlDialectMapping(project, null, null)
        } finally {
            super.tearDown()
        }
    }

    private fun accept(sql: String, name: String, completionChar: Char = '\n') {
        myFixture.configureByText("completion.sql", sql)
        val items = myFixture.completeBasic().orEmpty()
        val item = items.single { it.lookupString == name && it.`object` is FunctionPresentation }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(completionChar)
    }

    fun testFunctionInsertsCallWithCaretInArguments() {
        accept("SELECT ab<caret>", "abs")
        myFixture.checkResult("SELECT abs(<caret>)")
    }

    fun testExistingParenthesisIsReused() {
        accept("SELECT ab<caret>(1)", "abs")
        myFixture.checkResult("SELECT abs(<caret>1)")
    }

    fun testArgumentlessCallPlacesCaretAfterParentheses() {
        accept("SELECT ver<caret>", "version")
        myFixture.checkResult("SELECT version()<caret>")
    }

    fun testBareGrammarFormDoesNotInsertParentheses() {
        accept("SELECT current_time<caret>", "CURRENT_TIMESTAMP")
        myFixture.checkResult("SELECT CURRENT_TIMESTAMP<caret>")
    }

    fun testSpecialGrammarFormDoesNotInsertParentheses() {
        accept("SELECT ca<caret>", "CAST")
        myFixture.checkResult("SELECT CAST<caret>")
    }

    fun testTypedParenthesisAcceptsBareFormAsCall() {
        accept("SELECT current_time<caret>", "CURRENT_TIMESTAMP", '(')
        myFixture.checkResult("SELECT CURRENT_TIMESTAMP(<caret>)")
    }

    fun testMultiKindFunctionsReachCompletion() {
        for ((name, kinds) in listOf(
            "range" to setOf(FunctionKind.SCALAR, FunctionKind.TABLE_VALUED),
            "generate_series" to setOf(FunctionKind.SCALAR, FunctionKind.TABLE_VALUED),
            "repeat" to setOf(FunctionKind.SCALAR, FunctionKind.TABLE_VALUED),
            "histogram" to setOf(FunctionKind.AGGREGATE, FunctionKind.TABLE_VALUED),
        )) {
            for (table in listOf(false, true)) {
                myFixture.configureByText("kinds.sql", "${if (table) "SELECT * FROM" else "SELECT"} ${name.take(3)}<caret>")
                val entries = myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
                assertEquals("$name table=$table", kinds.filter { (it == FunctionKind.TABLE_VALUED) == table }.toSet(),
                    entries.filter { it.name == name }.map { it.kind }.toSet())
            }
        }
    }

    fun testNonCallableHelpersDoNotReachOfflineFunctionCompletion() {
        for ((prefix, removed) in listOf(
            "show_t" to setOf("show_tables"),
            "pandas_" to setOf("pandas_scan"),
            "python_" to setOf("python_map_function"),
            "create_fts" to setOf("create_fts_index"),
        )) {
            myFixture.configureByText("removed.sql", "SELECT $prefix<caret>")
            val entries = myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
            assertFalse(entries.toString(), entries.any { it.name in removed })
        }
    }

    fun testDuckdb2IcuFunctionsReachOfflineCompletion() {
        val expected = setOf("icu_collate_fy", "icu_collate_lij", "icu_collate_nso", "icu_collate_st", "icu_collate_tn")
        val native = mutableSetOf<String>()
        java.sql.DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT function_name FROM duckdb_functions() WHERE function_type = 'scalar' AND function_name LIKE 'icu_collate_%'").use { rows ->
                    while (rows.next()) native += rows.getString(1)
                }
            }
        }
        assertTrue("DuckDB 2.0 must actually register the restored ICU functions", native.containsAll(expected))
        myFixture.configureByText("icu.sql", "SELECT icu_collate_<caret>")
        val entries = myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
        assertTrue("Offline completion must match the 2.0 registry", entries.map { it.name }.toSet().containsAll(expected))
    }

    fun testMetadataKeywordsReachExpressionCompletion() {
        myFixture.configureByText("keywords.sql", "SELECT ab<caret>")
        val names = myFixture.completeBasic().orEmpty().map { it.lookupString }
        assertTrue(names.toString(), names.containsAll(listOf("ABORT", "ABSOLUTE")))
    }
}
