package dev.sort.duckdb.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.DocumentationProviderContract

class DuckdbDocumentationProviderTest : BasePlatformTestCase() {
    fun testEngineDocsKeepPlatformTableAndFunctionDocs() =
        DocumentationProviderContract(myFixture, DuckdbSqlDialect, testRootDisposable)
            .check("CRE<caret>ATE TABLE t (k INTEGER)", "duckdb.org", table = "orders")
}
