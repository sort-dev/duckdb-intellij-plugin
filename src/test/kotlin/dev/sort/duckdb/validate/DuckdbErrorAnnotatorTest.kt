package dev.sort.duckdb.validate

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.lang.annotation.HighlightSeverity
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorProfile
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import dev.sort.duckdb.pipes.DuckdbPipesSettings
import dev.sort.duckdb.brikk.BrikkDuckdbProfile
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.sort.duckdb.sql.DuckdbSqlDialect

/** Pins the Stage-3 editor surface: engine parser errors appear as ERROR annotations, valid DuckDB stays clean. */
class DuckdbErrorAnnotatorTest : BasePlatformTestCase() {

    private var counter = 0

    private fun highlight(sql: String): List<HighlightInfo> {
        val psi = myFixture.configureByText("v${counter++}.sql", sql)
        setSqlDialectMapping(project, psi.virtualFile, DuckdbSqlDialect)
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
        setSqlDialectMapping(project, psi.virtualFile, DuckdbSqlDialect)
        val file = requireNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile))
        val annotator = DuckdbErrorAnnotator()
        val info = annotator.collectInformation(file)
        assertNotNull("collect must produce statements", info)
        val result = requireNotNull(annotator.doAnnotate(info))
        val errors = appliedAnnotations(file, annotator, result)
        assertEquals(1, errors.size)
        assertTrue(errors.single().message, errors.single().message.contains("Parser Error"))
        assertEquals(listOf(TextRange(sql.indexOf("WHERE"), sql.indexOf("WHERE") + 5)), appliedRanges(file, annotator, result))
    }

    fun testSupplementaryCharactersAndCrLfKeepExactErrorRanges() {
        for (newline in listOf("\n", "\r\n")) {
            val sql = "SELECT '😊😊😊😊'${newline}FROM WHERE"
            val native = DuckdbEngineValidator.verdicts(listOf(sql)).single() as dev.brikk.house.sql.verify.VerifyResult.Rejected
            assertEquals(2, native.error.line)
            assertEquals(sql.indexOf("WHERE"), native.error.offset)
            val psi = myFixture.configureByText("coordinates${counter++}.sql", sql)
            setSqlDialectMapping(project, psi.virtualFile, DuckdbSqlDialect)
            val file = requireNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile))
            val annotator = DuckdbErrorAnnotator()
            val result = requireNotNull(annotator.doAnnotate(annotator.collectInformation(file)))
            val start = file.text.indexOf("WHERE")
            assertEquals(listOf(TextRange(start, start + 5)), appliedRanges(file, annotator, result))
        }
    }

    private fun appliedAnnotations(file: PsiFile, annotator: NativeErrorAnnotator, result: NativeErrorAnnotator.Outcome): List<com.intellij.lang.annotation.Annotation> {
        val annotations = AnnotationHolderImpl(AnnotationSession(file), false)
        annotations.runAnnotatorWithContext(file, Annotator { _, holder -> annotator.apply(file, result, holder) })
        annotations.assertAllAnnotationsCreated()
        return annotations.filter { it.severity == HighlightSeverity.ERROR }
    }

    private fun appliedRanges(file: PsiFile, annotator: NativeErrorAnnotator, result: NativeErrorAnnotator.Outcome): List<TextRange> =
        appliedAnnotations(file, annotator, result).map { TextRange(it.startOffset, it.endOffset) }

    private fun file(sql: String): PsiFile {
        val psi = myFixture.configureByText("native${counter++}.sql", sql)
        setSqlDialectMapping(project, psi.virtualFile, DuckdbSqlDialect)
        return requireNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(psi.virtualFile))
    }

    fun testDefaultsAndStableDriverValidator() {
        val file = file("SELECT 1")
        assertEquals(100_000, DuckdbNativeErrorProfile.sizeCap)
        assertFalse(DuckdbNativeErrorProfile.localParser)
        assertFalse(DuckdbNativeErrorProfile.reportsPsiErrors)
        assertNull(DuckdbNativeErrorProfile.budget)
        assertEquals(kotlin.time.Duration.ZERO, DuckdbNativeErrorProfile.debounce)
        assertSame(DuckdbNativeErrorProfile.validator(file), DuckdbNativeErrorProfile.validator(file))
    }

    fun testLargeStatementIsSkippedButFollowingStatementIsChecked() {
        val sql = "SELECT '" + "x".repeat(100_001) + "' FROM WHERE; SELECT FROM WHERE"
        val file = file(sql)
        val annotator = DuckdbErrorAnnotator()
        val result = requireNotNull(annotator.doAnnotate(annotator.collectInformation(file)))
        val start = sql.lastIndexOf("WHERE")
        assertEquals(listOf(TextRange(start, start + 5)), appliedRanges(file, annotator, result))
    }

    fun testEditDiscardsStaleNativeResult() {
        val file = file("SELECT FROM WHERE")
        val annotator = DuckdbErrorAnnotator()
        val result = requireNotNull(annotator.doAnnotate(annotator.collectInformation(file)))
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("SELECT 1") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEmpty(appliedRanges(file, annotator, result))
    }

    fun testPipeSettingAndBrikkMetadataDiscardStaleResultsWithoutAnEdit() {
        val settings = project.service<DuckdbPipesSettings>()
        try {
            settings.enabled = true
            val file = file("FROM t |> BOGUS 2")
            val annotator = DuckdbErrorAnnotator()
            val result = requireNotNull(annotator.doAnnotate(annotator.collectInformation(file)))
            assertEquals(1, appliedRanges(file, annotator, result).size)
            settings.enabled = false
            assertEmpty(appliedRanges(file, annotator, result))

            file.putUserData(BrikkDuckdbProfile.KEY, BrikkDuckdbProfile(emptyList(), listOf("rows"), emptyList()))
            val brikkResult = requireNotNull(annotator.doAnnotate(annotator.collectInformation(file)))
            val revision = DuckdbNativeErrorProfile.modificationCount(file)
            file.putUserData(BrikkDuckdbProfile.KEY, BrikkDuckdbProfile(emptyList(), listOf("other"), listOf("pending")))
            assertTrue(DuckdbNativeErrorProfile.modificationCount(file) > revision)
            assertEmpty(appliedRanges(file, annotator, brikkResult))
        } finally { settings.enabled = false }
    }

    fun testValidDuckdbSyntaxStaysClean() {
        val sql = "SELECT * EXCLUDE (secret) FROM t QUALIFY row_number() OVER () = 1;\n" +
            "FROM range(10) SELECT *;\nSUMMARIZE t2;"
        val errors = highlight(sql).filter { it.description?.startsWith("DuckDB:") == true }
        assertTrue("valid DuckDB must produce no engine errors, got: ${errors.map { it.description }}", errors.isEmpty())
    }

    fun testUnverifiedAndUnavailableProduceNoNativeAnnotations() {
        val sql = "CREATE TABLE never_created AS SELECT * FROM read_csv('/not-readable.csv')"
        val psi = file(sql)
        for (jar in listOf(null, "/missing/duckdb.jar")) {
            val validator = service<DuckdbEngineDriverService>().drivers.validatorForJar(jar)
            val annotator = object : NativeErrorAnnotator(object : NativeErrorProfile by DuckdbNativeErrorProfile {
                override fun validator(file: PsiFile) = validator
            }) {}
            val result = requireNotNull(annotator.doAnnotate(annotator.collectInformation(psi)))
            assertEmpty(appliedRanges(psi, annotator, result))
        }
    }
}
