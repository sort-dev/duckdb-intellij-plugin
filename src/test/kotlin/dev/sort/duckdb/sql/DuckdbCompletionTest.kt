package dev.sort.duckdb.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping

class DuckdbCompletionTest : BasePlatformTestCase() {

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
