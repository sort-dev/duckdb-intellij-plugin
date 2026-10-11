package dev.sort.duckdb.validate

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.lang.annotation.HighlightSeverity
import dev.sort.duckdb.setSqlDialectMapping
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.sort.duckdb.sql.DuckdbSqlDialect

/** Pins the Stage-3 editor surface: engine parser errors appear as ERROR annotations, valid DuckDB stays clean. */
class DuckdbErrorAnnotatorTest : BasePlatformTestCase() {

    private var counter = 0

    private fun highlight(sql: String): List<HighlightInfo> {
        val psi = myFixture.configureByText("v${counter++}.sql", sql)
        setSqlDialectMapping(project, psi.virtualFile, DuckdbSqlDialect.INSTANCE)
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
    }

    fun testAnnotatorPipelineFlagsEngineError() {
        // Unit-drives the annotator pipeline (collect -> doAnnotate): whether the light fixture
        // schedules ExternalToolPass is a test-infra question; the in-editor squiggle is on the
        // IDE dogfood checklist (the doris annotator was verified the same way).
        // NB: must be a PARSER error to the engine — PG-lineage parsers accept arbitrary
        // operator sequences (x ==== 2 is a BINDER error: unknown operator), which the validator
        // correctly leaves alone.
        val sql = "SELECT 1;\nSELECT FROM WHERE;"
        val psi = myFixture.configureByText("u.sql", sql)
        setSqlDialectMapping(project, psi.virtualFile, DuckdbSqlDialect.INSTANCE)
        val file = requireNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile))
        val annotator = DuckdbErrorAnnotator()
        val info = annotator.collectInformation(file)
        assertNotNull("collect must produce statements", info)
        val result = requireNotNull(annotator.doAnnotate(info))
        assertTrue("engine must flag the broken statement", result.errors.isNotEmpty())
        val (range, err) = result.errors.first()
        assertTrue("message carries the engine's words: ${err.message}", err.message.contains("Parser Error"))
        assertEquals(sql.indexOf("SELECT FROM"), range.startOffset)
        assertEquals(listOf(TextRange(sql.indexOf("WHERE"), sql.indexOf("WHERE") + 5)), appliedRanges(file, annotator, result))
    }

    fun testSupplementaryCharactersAndCrLfKeepExactErrorRanges() {
        for (newline in listOf("\n", "\r\n")) {
            val sql = "SELECT '😊😊😊😊'${newline}FROM WHERE"
            val native = DuckdbEngineValidator.verdicts(listOf(sql)).single() as DuckdbEngineValidator.Verdict.Flagged
            assertEquals(2, native.error.line)
            assertEquals(sql.indexOf("WHERE"), native.error.offset)
            val psi = myFixture.configureByText("coordinates${counter++}.sql", sql)
            setSqlDialectMapping(project, psi.virtualFile, DuckdbSqlDialect.INSTANCE)
            val file = requireNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile))
            val annotator = DuckdbErrorAnnotator()
            val result = requireNotNull(annotator.doAnnotate(annotator.collectInformation(file)))
            val start = file.text.indexOf("WHERE")
            assertEquals(listOf(TextRange(start, start + 5)), appliedRanges(file, annotator, result))
        }
    }

    private fun appliedRanges(file: PsiFile, annotator: DuckdbErrorAnnotator, result: DuckdbErrorAnnotator.Result): List<TextRange> {
        val annotations = AnnotationHolderImpl(AnnotationSession(file), false)
        annotations.runAnnotatorWithContext(file, Annotator { _, holder -> annotator.apply(file, result, holder) })
        annotations.assertAllAnnotationsCreated()
        return annotations.filter { it.severity == HighlightSeverity.ERROR }.map { TextRange(it.startOffset, it.endOffset) }
    }

    fun testValidDuckdbSyntaxStaysClean() {
        val sql = "SELECT * EXCLUDE (secret) FROM t QUALIFY row_number() OVER () = 1;\n" +
            "FROM range(10) SELECT *;\nSUMMARIZE t2;"
        val errors = highlight(sql).filter { it.description?.startsWith("DuckDB:") == true }
        assertTrue("valid DuckDB must produce no engine errors, got: ${errors.map { it.description }}", errors.isEmpty())
    }
}
