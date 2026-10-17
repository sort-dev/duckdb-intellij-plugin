package dev.sort.duckdb.catalog

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.database.psi.DbPsiFacade
import com.intellij.sql.database.SqlDataSourceManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.AccessToken
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.sql.dialects.SqlResolveMappings
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.sql.DuckdbSqlDialect
import dev.sort.duckdb.syncAndWait
import java.util.concurrent.CopyOnWriteArrayList

/** Plain SQL editor smoke, also run with the optional Kotlin plugin absent. */
class DuckdbPlainSqlSmokeTest : DuckdbCatalogTestCase() {
    private val logged = CopyOnWriteArrayList<Throwable>()
    private var logging: AccessToken? = null
    private var autocomplete = false

    override fun setUp() {
        logging = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                logged += t ?: AssertionError(message)
                return super.processError(category, message, details, t)
            }
        })
        try { super.setUp() } catch (failure: Throwable) {
            logging?.finish()
            throw failure
        }
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        setSqlDialectMapping(project, null, DuckdbSqlDialect)
    }

    override fun tearDown() {
        try {
            LookupManager.getInstance(project).hideActiveLookup()
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            SqlResolveMappings.getInstance(project).setMapping(null, null)
            super.tearDown()
        } finally { logging?.finish() }
        assertTrue("Startup/editor work logged errors: $logged", logged.isEmpty())
    }

    fun testModelTablesAndColumnsCompleteInOrdinarySql() {
        val schema = myFixture.addFileToProject("smoke-schema.sql", "CREATE TABLE orders (id BIGINT NOT NULL, region VARCHAR);")
        setSqlDialectMapping(project, schema.virtualFile, DuckdbSqlDialect)
        val manager = SqlDataSourceManager.getInstance(project)
        val dataSource = manager.createEmpty().apply {
            name = "Smoke model"; definedDbms = DuckdbDbms.DUCKDB_BRIKK; setFiles(arrayOf(schema.virtualFile))
        }
        manager.addDataSource(dataSource)
        try {
            dataSource.syncAndWait()
            DbPsiFacade.getInstance(project).clearCaches()
            SqlResolveMappings.getInstance(project).setMapping(null, SqlResolveMappings.ALL_DATA_SOURCES)
            myFixture.configureByText("table.sql", "SELECT * FROM ord<caret>")
            var names = myFixture.completeBasic().orEmpty().map { it.lookupString }
            assertTrue("Model table missing: $names", "orders" in names)
            LookupManager.getInstance(project).hideActiveLookup()
            myFixture.configureByText("columns.sql", "SELECT o.<caret> FROM orders o")
            names = myFixture.completeBasic().orEmpty().map { it.lookupString }
            assertTrue("Model columns missing: $names", names.containsAll(listOf("id", "region")))
        } finally { manager.removeDataSource(dataSource) }
    }

    fun testRealisticDuckdbStatementsHaveNoFalseErrorHighlights() {
        val statements = listOf(
            """WITH orders AS (SELECT * FROM (VALUES (1, 'x', 2.5), (2, 'y', 3.5)) AS t(id, region, amount))
                SELECT * EXCLUDE (region), row_number() OVER (PARTITION BY region ORDER BY amount DESC) AS rn
                FROM orders QUALIFY rn = 1 ORDER BY id LIMIT 5;""".trimIndent(),
            "SELECT {'region': 'x', 'amount': 42} AS payload, [1, 2, 3] AS ids, DATE '2026-10-13' AS day;",
            "FROM range(10) SELECT range, range * 2 AS doubled WHERE range > 2;",
        )
        statements.forEachIndexed { index, sql ->
            myFixture.configureByText("highlight-$index.sql", sql)
            val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
            assertEmpty("False error on valid DuckDB SQL: $sql\n$errors", errors)
        }
    }

    fun testKotlinOptionalStartupAndBasicEditorWorkLogNoErrors() {
        if (System.getProperty("test.kotlin") == "absent") {
            assertNull("Kotlin must not be loaded in this lane", PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.kotlin"))?.pluginClassLoader)
        }
        myFixture.configureByText("startup.sql", "SELECT 1")
        assertEmpty(myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR })
        assertTrue(logged.toString(), logged.isEmpty())
    }
}
