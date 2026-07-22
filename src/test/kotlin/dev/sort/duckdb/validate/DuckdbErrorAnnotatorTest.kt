package dev.sort.duckdb.validate

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.sql.DuckdbSqlDialect

/** Pins the Stage-3 editor surface: engine parser errors appear as ERROR annotations, valid DuckDB stays clean. */
class DuckdbErrorAnnotatorTest : BasePlatformTestCase() {

    private var counter = 0

    private fun highlight(sql: String): List<HighlightInfo> {
        val psi = myFixture.configureByText("v${counter++}.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DuckdbSqlDialect.INSTANCE)
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
    }

    fun testAnnotatorPipelineFlagsEngineError() {
        // Unit-drives the annotator pipeline (collect -> doAnnotate): whether the light fixture
        // schedules ExternalToolPass is a test-infra question; the in-editor squiggle is on the
        // IDE dogfood checklist (the doris annotator was verified the same way).
        val sql = "SELECT 1;\nSELECT * FROM t WHERE x ==== 2;"
        val psi = myFixture.configureByText("u.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DuckdbSqlDialect.INSTANCE)
        val file = com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile)!!
        val annotator = DuckdbErrorAnnotator()
        val info = annotator.collectInformation(file)
        assertNotNull("collect must produce statements", info)
        val result = annotator.doAnnotate(info)!!
        assertTrue("engine must flag the broken statement", result.errors.isNotEmpty())
        val (range, err) = result.errors.first()
        assertTrue("message carries the engine's words: ${err.message}", err.message.contains("Parser Error"))
        assertTrue("error anchored to the broken statement", range.startOffset >= sql.indexOf("SELECT *"))
    }

    fun testValidDuckdbSyntaxStaysClean() {
        val sql = "SELECT * EXCLUDE (secret) FROM t QUALIFY row_number() OVER () = 1;\n" +
            "FROM range(10) SELECT *;\nSUMMARIZE t2;"
        val errors = highlight(sql).filter { it.description?.startsWith("DuckDB:") == true }
        assertTrue("valid DuckDB must produce no engine errors, got: ${errors.map { it.description }}", errors.isEmpty())
    }
}
