package dev.sort.duckdb.sql

import com.intellij.database.psi.DbPsiFacade
import com.intellij.openapi.application.ApplicationManager
import com.intellij.sql.database.SqlDataSourceManager
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.setSqlDialectMapping

class DuckdbColumnCompletionTest : BasePlatformTestCase() {
    fun testSelectListColumnsSurviveUnfilteredFunctionsAndKeywords() {
        val schema = myFixture.addFileToProject("schema.sql",
            "CREATE TABLE customers (customer_id INTEGER, customer_name TEXT, account_balance DECIMAL(12,2));")
        setSqlDialectMapping(project, schema.virtualFile, DuckdbSqlDialect.INSTANCE)
        val manager = SqlDataSourceManager.getInstance(project)
        val source = manager.createEmpty().apply {
            name = "DuckDB completion schema"
            definedDbms = DuckdbDbms.DUCKDB_BRIKK
            setFiles(arrayOf(schema.virtualFile))
        }
        manager.addDataSource(source)
        try {
            PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread {
                source.sync()
                source.javaClass.methods.firstOrNull { it.name == "waitComputed" && it.parameterCount == 0 }?.invoke(source)
            })
            DbPsiFacade.getInstance(project).clearCaches()
            SqlResolveMappings.getInstance(project).setMapping(null, SqlResolveMappings.ALL_DATA_SOURCES)
            setSqlDialectMapping(project, null, DuckdbSqlDialect.INSTANCE)
            myFixture.configureByText("query.sql", "SELECT <caret> FROM customers")
            val names = myFixture.completeBasic().orEmpty().map { it.lookupString }
            println("DuckDB SELECT-list completion: ${names.size} variants; columns=${names.filter { it in setOf("customer_id", "customer_name", "account_balance") }}")
            assertTrue(names.toString(), names.containsAll(listOf("customer_id", "customer_name", "account_balance")))
        } finally {
            manager.removeDataSource(source)
            setSqlDialectMapping(project, null, null)
        }
    }
}
