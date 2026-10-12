package dev.sort.duckdb.brikk

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.brikk.kotlin.BrikkDuckdbInjectionPerformer
import dev.sort.duckdb.brikk.kotlin.BrikkDuckdbKotlinContext
import dev.sort.duckdb.pipes.DuckdbPipeDialect
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor
import dev.brikk.house.sql.intellij.pipe.isEditingEnabled
import dev.sort.duckdb.pipes.DuckdbPipesSettings
import dev.sort.duckdb.sql.DuckdbSqlDialect
import dev.sort.duckdb.syncAndWait
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/** Exercise real Kotlin resolution and injection, not a fake replacement string or SQL lexer alone. */
open class BrikkDuckdbInjectionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val stdlib = java.io.File(com.intellij.util.PathUtil.getJarPathForClass(kotlin.Unit::class.java))
        com.intellij.testFramework.PsiTestUtil.addLibrary(module, "kotlin-stdlib", stdlib.parent, stdlib.name)
        myFixture.addFileToProject("org/intellij/lang/annotations/Language.java", """
            package org.intellij.lang.annotations;
            public @interface Language { String value(); String prefix() default ""; String suffix() default ""; }
        """.trimIndent())
        myFixture.addFileToProject("runtime/Types.kt", """
            package dev.brikk.house.sql.runtime
            import org.intellij.lang.annotations.Language
            annotation class BrikkSql
            annotation class BrikkSqlDialect(val dialect: String)
            interface Rel<T>
            object Sql {
                fun duckdb(@Language("DuckDBSQL", prefix = "-- dialect: duckdb\n") sql: String): Rel<Nothing> = error("compiler")
                fun doris(@Language("DorisSQL") sql: String): Rel<Nothing> = error("compiler")
                fun trino(@Language("TrinoSQL") sql: String): Rel<Nothing> = error("compiler")
            }
        """.trimIndent())
    }

    protected fun configure(body: String): KtStringTemplateExpression {
        myFixture.configureByText("Sample.kt", """
            import dev.brikk.house.sql.runtime.*
            import kotlin.text.trimIndent
            import kotlin.text.trimMargin
            $body
        """.trimIndent())
        val hostFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)
        return PsiTreeUtil.findChildrenOfType(hostFile, KtStringTemplateExpression::class.java)
            .last { it.text.contains("SELECT") || it.text.contains("FROM") }
    }
    protected fun injected(host: KtStringTemplateExpression): PsiFile {
        val injections = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host).orEmpty()
        assertFalse("No SQL injection for ${host.text}", injections.isEmpty())
        val file = injections.first().first.containingFile
        assertEquals(DuckdbSqlDialect.INSTANCE, file.language)
        return file
    }

    fun testScalarReferenceIsANamedBindOnInitialInjection() {
        val performers = com.intellij.lang.injection.general.LanguageInjectionPerformer.INJECTOR_EXTENSION
            .allForLanguage(org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE).map { it.javaClass.name }
        assertTrue(performers.toString(), performers.first().contains("BrikkDuckdbInjectionPerformer"))
        val host = configure("@BrikkSql fun customers(tenant: String) = Sql.duckdb(\"SELECT tenant FROM customers WHERE tenant = \$tenant\")")
        val context = BrikkDuckdbKotlinContext.find(host)
        assertNotNull("Resolved entry point/annotation", context)
        val file = injected(host)
        assertTrue(file.text, file.text.contains("tenant = :tenant"))
        assertFalse(file.text.contains("tenant = tenant"))
        assertEquals(listOf("tenant"), BrikkDuckdbProfile.forFile(file)!!.binds)
        assertFalse(project.service<DuckdbPipesSettings>().enabled)
        assertTrue(DuckdbPipeDialect.isEditingEnabled(file))
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        val ref = host.entries.mapNotNull { it.expression as? KtNameReferenceExpression }.single()
        assertNotNull(ref.mainReference.resolve())
    }

    fun testBracedRefsSlotsConstantsAndLocalsHaveDistinctRoles() {
        val host = configure("""
            const val COLS = "id, tenant"
            @BrikkSql fun query(src: Rel<Nothing>, minId: Int) {
                val local = minId
                return Sql.duckdb("FROM ${'$'}src() |> WHERE id > ${'$'}{minId} |> SELECT ${'$'}COLS, ${'$'}local AS threshold")
            }
        """.trimIndent())
        val file = injected(host)
        assertTrue(file.text, file.text.contains("FROM src() |> WHERE id > :minId |> SELECT id, tenant, :local AS threshold"))
        val profile = BrikkDuckdbProfile.forFile(file)!!
        assertEquals(listOf("src"), profile.slots)
        assertEquals(listOf("minId", "local"), profile.binds)
        assertEmpty(profile.issues)
    }

    fun testUnrelatedAndLookalikeCallsAreNotClaimed() {
        val unrelated = configure("fun unrelated(tenant: String) = Sql.duckdb(\"SELECT \$tenant\")")
        assertNull(BrikkDuckdbKotlinContext.find(unrelated))
        val lookalike = configure("""
            annotation class BrikkSql
            object Fake { fun duckdb(sql: String) = sql }
            @BrikkSql fun notBrikk(tenant: String) = Fake.duckdb("SELECT ${'$'}tenant")
        """.trimIndent())
        assertNull(BrikkDuckdbKotlinContext.find(lookalike))
        val doris = configure("@BrikkSql fun doris(tenant: String) = Sql.doris(\"SELECT \$tenant\")")
        assertNull(BrikkDuckdbKotlinContext.find(doris))
        val fakeAnnotation = configure("""
            annotation class BrikkSql
            @BrikkSql fun notBrikk(tenant: String) = Sql.duckdb("SELECT ${'$'}tenant")
        """.trimIndent())
        assertNull(BrikkDuckdbKotlinContext.find(fakeAnnotation))
    }

    fun testHelperRecognizesTrinoWithoutDuckdbClaimingTheHost() {
        val host = configure("@BrikkSql fun query(tenant: String) = Sql.trino(\"SELECT \$tenant\")")
        val shared = dev.brikk.house.intellij.BrikkKotlinContext.inspect(host, includeShapes = false)
        assertTrue(shared.toString(), shared is dev.brikk.house.intellij.ContextResult.Available)
        assertEquals(dev.brikk.house.intellij.BrikkDialect.TRINO,
            (shared as dev.brikk.house.intellij.ContextResult.Available).context.dialect)
        assertNull(BrikkDuckdbKotlinContext.find(host))
        val injections = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host).orEmpty()
        assertTrue(injections.none { BrikkDuckdbProfile.forFile(it.first.containingFile) != null })
    }

    fun testComplexInterpolationAndComputedConstAreRefused() {
        val host = configure("""
            const val COMPUTED = "id" + ", tenant"
            fun normalize(s: String) = s
            @BrikkSql fun query(tenant: String) = Sql.duckdb("SELECT ${'$'}COMPUTED WHERE tenant = ${'$'}{normalize(tenant)}")
        """.trimIndent())
        val profile = BrikkDuckdbProfile.forFile(injected(host))!!
        assertEquals(2, profile.issues.size)
        assertTrue(profile.issues.any { it.contains("const val") })
        assertTrue(profile.issues.any { it.contains("extract") })
    }

    fun testTrimmedMultilineAndJsonPathsKeepHostRanges() {
        val host = configure("""
            @BrikkSql fun query(tenant: String) = Sql.duckdb(""${'"'}
                SELECT json_extract_string(payload, '${'$'}.customer_id') AS name, '😀' AS face
                FROM customers
                WHERE tenant = ${'$'}tenant
            ""${'"'}.trimIndent())
        """.trimIndent())
        assertNotNull("Trimmed context should resolve", BrikkDuckdbKotlinContext.find(host))
        val file = injected(host)
        assertTrue(file.text, file.text.contains("'$.customer_id'"))
        assertTrue(file.text, file.text.contains("tenant = :tenant"))
        val offset = file.text.indexOf("payload")
        val actual = InjectedLanguageManager.getInstance(project).injectedToHost(file, offset)
        assertEquals(host.textRange.startOffset + host.text.indexOf("payload"), actual)
        val context = BrikkDuckdbKotlinContext.find(host)!!
        val places = BrikkDuckdbInjectionPerformer().injectionPlaces(BrikkDuckdbKotlinContext.template(context))
        assertTrue(places.isNotEmpty())
    }

    fun testRenamePreservesKotlinReferenceAndUpdatesVirtualBind() {
        var host = configure("@BrikkSql fun query(tenant: String) = Sql.duckdb(\"SELECT tenant WHERE tenant = \$tenant\")")
        assertTrue(injected(host).text.contains(":tenant"))
        val parameter = PsiTreeUtil.findChildrenOfType(myFixture.file, KtNamedFunction::class.java)
            .single { it.name == "query" }.valueParameters.single()
        RenameProcessor(project, parameter, "account", false, false).run()
        host = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).single()
        assertTrue(host.text, host.text.contains("\$account"))
        assertTrue(injected(host).text.contains(":account"))
    }

    fun testUnresolvedBindIsNotManufacturedIntoAColumnAndRefreshesAfterEdit() {
        var host = configure("@BrikkSql fun query(tenant: String) = Sql.duckdb(\"SELECT tenant WHERE tenant = \$missing\")")
        val file = injected(host)
        assertTrue(file.text, file.text.contains(":__brikk_pending_"))
        assertTrue(BrikkDuckdbProfile.forFile(file)!!.issues.isNotEmpty())
        WriteCommandAction.runWriteCommandAction(project) {
            val i = myFixture.editor.document.text.indexOf("\$missing")
            myFixture.editor.document.replaceString(i, i + "\$missing".length, "\$tenant")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        host = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).single()
        assertTrue(injected(host).text.contains(":tenant"))
    }

    fun testDdlBackedInspectionsKeepColumnsAndBindsDistinct() {
        val schema = myFixture.addFileToProject("schema.sql", "CREATE TABLE customers (tenant TEXT NOT NULL, id INTEGER);")
        dev.sort.duckdb.setSqlDialectMapping(project, schema.virtualFile, DuckdbSqlDialect.INSTANCE)
        val manager = com.intellij.sql.database.SqlDataSourceManager.getInstance(project)
        val source = manager.createEmpty().apply {
            name = "Brikk schema test"
            definedDbms = dev.sort.duckdb.DuckdbDbms.DUCKDB_BRIKK
            setFiles(arrayOf(schema.virtualFile))
        }
        manager.addDataSource(source)
        try {
            source.syncAndWait()
            com.intellij.database.psi.DbPsiFacade.getInstance(project).clearCaches()
            com.intellij.sql.dialects.SqlResolveMappings.getInstance(project).setMapping(null,
                com.intellij.sql.dialects.SqlResolveMappings.ALL_DATA_SOURCES)
            val inspections = com.intellij.codeInspection.ex.InspectionToolRegistrar.getInstance().createTools()
                .filter { it.shortName in setOf("SqlConstantExpressionInspection", "SqlResolveInspection") }
                .mapNotNull { it.tool as? com.intellij.codeInspection.LocalInspectionTool }
            assertEquals(2, inspections.size)
            myFixture.enableInspections(*inspections.toTypedArray())
            var host = configure("@BrikkSql fun query(tenant: String) = Sql.duckdb(\"SELECT tenant FROM customers WHERE tenant = \$tenant\")")
            val sql = injected(host)
            assertTrue(com.intellij.sql.dialects.SqlDataSourceMappings.getInstance(project).getDataSources(sql).isNotEmpty())
            val warnings = myFixture.doHighlighting().mapNotNull { it.description }
            assertFalse(warnings.toString(), warnings.any { it.contains("always", true) && it.contains("true", true) })
            host = configure("@BrikkSql fun query(tenant: String) = Sql.duckdb(\"SELECT tenant FROM customers WHERE tenant = tenant\")")
            injected(host)
            val ordinaryWarnings = myFixture.doHighlighting().mapNotNull { it.description }
            assertTrue("The real column=column inspection must remain active: $ordinaryWarnings",
                ordinaryWarnings.any { it.contains("always", true) && it.contains("true", true) })
        } finally { manager.removeDataSource(source) }
    }

    fun testMarginEscapesAndPlainNamedBinds() {
        val host = configure("""
            @BrikkSql fun query(tenant: String) = Sql.duckdb(""${'"'}
                |SELECT '${'$'}{'$'}.customer_id', tenant
                |FROM customers
                |WHERE tenant = :tenant OR tenant = ${'$'}tenant
            ""${'"'}.trimMargin())
        """.trimIndent())
        val file = injected(host)
        assertTrue(file.text, file.text.contains("'$.customer_id'"))
        assertTrue(file.text, file.text.contains("WHERE tenant = :tenant OR tenant = :tenant"))
        assertFalse(file.text.contains("|SELECT"))
    }

    fun testRegularStringEscapesAndSupportedPropertyAreNeverEvaluated() {
        val host = configure("""
            val threshold: Int get() = error("Do not execute IDE getters")
            @BrikkSql fun query(id: Int) = Sql.duckdb("SELECT 'a\\b', id\nFROM customers WHERE id = ${'$'}threshold AND id = ${'$'}{id}")
        """.trimIndent())
        val file = injected(host)
        val virtualSql = InjectedLanguageManager.getInstance(project).getUnescapedText(file)
        assertTrue(virtualSql, virtualSql.contains("\nFROM customers"))
        assertTrue(file.text, file.text.contains("id = :threshold AND id = :id"))
        assertEquals(listOf("threshold", "id"), BrikkDuckdbProfile.forFile(file)!!.binds)
        val offset = InjectedLanguageManager.getInstance(project).mapUnescapedOffsetToInjected(file, virtualSql.indexOf("customers"))
        assertEquals(host.textRange.startOffset + host.text.indexOf("customers"),
            InjectedLanguageManager.getInstance(project).injectedToHost(file, offset))
        val annotator = dev.sort.duckdb.validate.DuckdbErrorAnnotator()
        val info = annotator.collectInformation(file)!!
        assertEquals(virtualSql, info.brikkText)
        assertNull(info.engineJar)
        assertEmpty(info.statements)
        assertEmpty(annotator.doAnnotate(info)!!.pipeErrors)
    }

    fun testResolvedTypeAliasStillClassifiesARelSlot() {
        val host = configure("""
            typealias Rows = Rel<Nothing>
            @BrikkSql fun query(src: Rows, id: Int) = Sql.duckdb("FROM ${'$'}src() |> WHERE id = ${'$'}id")
        """.trimIndent())
        val profile = BrikkDuckdbProfile.forFile(injected(host))!!
        assertEquals(listOf("src"), profile.slots)
        assertEquals(listOf("id"), profile.binds)
    }

    fun testTypeAndConstantEditsRefreshWithoutEditingTheSqlHost() {
        var host = configure("""
            const val COLS = "id"
            @BrikkSql fun query(src: Rel<Nothing>) = Sql.duckdb("SELECT ${'$'}COLS FROM ${'$'}src()")
        """.trimIndent())
        assertEquals(listOf("src"), BrikkDuckdbProfile.forFile(injected(host))!!.slots)
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            val i = document.text.indexOf("Rel<Nothing>")
            document.replaceString(i, i + "Rel<Nothing>".length, "String")
            val j = document.text.indexOf("\"id\"")
            document.replaceString(j, j + "\"id\"".length, "\"tenant\"")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        host = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).last()
        val file = injected(host)
        assertEquals(emptyList<String>(), BrikkDuckdbProfile.forFile(file)!!.slots)
        assertEquals(listOf("src"), BrikkDuckdbProfile.forFile(file)!!.binds)
        assertTrue(file.text, file.text.contains("SELECT tenant FROM :src()"))
    }

    fun testOwnExecuteAndRunToStageCannotSubmitInspectionPlaceholders() {
        val host = configure("@BrikkSql fun query(id: Int) = Sql.duckdb(\"FROM customers |> WHERE id = \$id\")")
        val file = injected(host)
        val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext("Brikk inspection view",
            com.intellij.openapi.actionSystem.Presentation(), com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, file).build())
        assertEquals(dev.brikk.house.sql.intellij.ExecutionClaim.HANDLED,
            PipeExecuteInterceptor(DuckdbPipeDialect).execute(event, com.intellij.database.settings.DatabaseSettings.ExecOption()))
        assertTrue(isBrikkInjectedSql(file))
        assertFalse(dev.sort.duckdb.pipes.RunPipesToStageIntention().isAvailable(project, myFixture.editor, file))
        assertTrue(DuckdbPipeDialect.enablesPreview(file))
        val previous = System.getProperty(DuckdbPipeDialect.vetoProperty)
        try {
            System.setProperty(DuckdbPipeDialect.vetoProperty, "false")
            assertFalse(DuckdbPipeDialect.isEditingEnabled(file))
        } finally {
            if (previous == null) System.clearProperty(DuckdbPipeDialect.vetoProperty)
            else System.setProperty(DuckdbPipeDialect.vetoProperty, previous)
        }
    }

    fun testRemovingAnnotationRestoresNativeInjectionWithoutProfileLeak() {
        var host = configure("@BrikkSql fun query(id: Int) = Sql.duckdb(\"SELECT \$id\")")
        assertNotNull(BrikkDuckdbProfile.forFile(injected(host)))
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            val i = document.text.indexOf("@BrikkSql ")
            document.deleteString(i, i + "@BrikkSql ".length)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        host = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).single()
        val file = injected(host)
        assertNull(BrikkDuckdbProfile.forFile(file))
        assertFalse(DuckdbPipeDialect.isEditingEnabled(file))
        assertTrue(file.text, file.text.contains("SELECT id"))
    }
    fun testMalformedAndMultipleSqlStatementsReportInsteadOfCrashing() {
        for (text in listOf("SELECT '", "SELECT 1; SELECT 2;", "SELECT 1 FROM")) {
            val host = configure("@BrikkSql fun query() = Sql.duckdb(\"$text\")")
            val file = injected(host)
            val annotator = dev.sort.duckdb.validate.DuckdbErrorAnnotator()
            val result = annotator.doAnnotate(annotator.collectInformation(file))!!
            assertEquals(1, result.pipeErrors.size)
            val annotations = com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl(
                com.intellij.lang.annotation.AnnotationSession(file), false)
            annotations.runAnnotatorWithContext(file, com.intellij.lang.annotation.Annotator { _, holder ->
                annotator.apply(file, result, holder)
            })
            annotations.assertAllAnnotationsCreated()
            val error = annotations.single { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
            if (text == "SELECT 1 FROM") {
                assertEquals(file.text.indexOf("FROM"), error.startOffset)
                assertEquals(file.text.indexOf("FROM") + 4, error.endOffset)
                assertNotNull(result.pipeErrors.single().second.range)
            } else {
                assertEquals(1, error.endOffset - error.startOffset)
                assertEquals(file.text.indexOf("SELECT"), error.startOffset)
            }
        }
    }

    fun testNativeInjectedPipeDiagnosticsUseUnescapedCoordinates() {
        val settings = project.service<DuckdbPipesSettings>()
        val previous = settings.enabled
        settings.enabled = true
        try {
            val host = configure("fun query() = Sql.duckdb(\"FROM t |> SELECT \\\"face\\\" AS face\\n |> BOGUS 2\")")
            val file = injected(host)
            assertNull(BrikkDuckdbProfile.forFile(file))
            val source = dev.brikk.house.sql.intellij.pipe.PipeText.of(file)
            assertTrue("Expected an escaped injection: ${file.text}", file.text.length > source.text.length)
            val annotator = dev.sort.duckdb.validate.DuckdbErrorAnnotator()
            val info = annotator.collectInformation(file)!!
            assertEquals(source.text, info.pipes.single().second)
            assertEmpty(info.statements)
            val result = annotator.doAnnotate(info)!!
            assertEquals(1, result.pipeErrors.size)
            val annotations = com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl(
                com.intellij.lang.annotation.AnnotationSession(file), false)
            annotations.runAnnotatorWithContext(file, com.intellij.lang.annotation.Annotator { _, holder ->
                annotator.apply(file, result, holder)
            })
            annotations.assertAllAnnotationsCreated()
            val error = annotations.single { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
            assertEquals(file.text.indexOf("BOGUS"), error.startOffset)
            assertEquals(file.text.indexOf("BOGUS") + 5, error.endOffset)
        } finally {
            settings.enabled = previous
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
