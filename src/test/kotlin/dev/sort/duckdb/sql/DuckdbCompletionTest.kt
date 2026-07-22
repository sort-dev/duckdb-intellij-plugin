package dev.sort.duckdb.sql

import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Pins the bundled-catalog completion: duckdb-only functions offered with kinds; catalog sane. */
class DuckdbCompletionTest : BasePlatformTestCase() {

    fun testCatalogLoadsRichInventories() {
        assertTrue("functions: ${DuckdbFunctionCatalog.functions.size}", DuckdbFunctionCatalog.functions.size > 500)
        assertTrue("keywords: ${DuckdbFunctionCatalog.keywords.size}", DuckdbFunctionCatalog.keywords.size > 300)
        val kinds = DuckdbFunctionCatalog.functions.associate { it.name to it.kind }
        assertEquals(DuckdbFunctionCatalog.Kind.TABLE, kinds["read_parquet"])
        assertEquals(DuckdbFunctionCatalog.Kind.SCALAR, kinds["list_transform"])
        assertTrue("no operator names in the completable list", kinds.keys.none { it.startsWith("!") })
    }

    fun testDuckdbFunctionsOfferedInExpression() {
        // mapping must precede file creation — a post-configure setMapping leaves the completion
        // session on the pre-mapping PSI (empirically bisected; highlight-only tests tolerate it)
        SqlDialectMappings.getInstance(project).setMapping(null, DuckdbSqlDialect.INSTANCE)
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
        SqlDialectMappings.getInstance(project).setMapping(null, DuckdbSqlDialect.INSTANCE)
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
