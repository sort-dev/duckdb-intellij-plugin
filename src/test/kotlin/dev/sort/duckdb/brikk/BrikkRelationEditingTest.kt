package dev.sort.duckdb.brikk

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.intellij.*
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

open class BrikkRelationEditingTest : BrikkDuckdbInjectionTest() {
    override fun setUp() {
        super.setUp()
        val jdk = com.intellij.openapi.projectRoots.JavaSdk.getInstance().createJdk("Brikk relation JDK", System.getProperty("java.home"), false)
        WriteCommandAction.runWriteCommandAction(project) { com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().addJdk(jdk, testRootDisposable) }
        com.intellij.openapi.roots.ModuleRootModificationUtil.setModuleSdk(module, jdk)
        myFixture.addFileToProject("runtime/Markers.kt", """
            package dev.brikk.house.sql.runtime
            interface Partial
            interface Shape : Partial
            annotation class BrikkTrait
        """.trimIndent())
    }
    private val traits = """
        import java.time.Instant
        import java.math.BigDecimal
        @BrikkTrait interface RawEvent : Partial { val id: Long; val tenant: String; val event_at: Instant }
        @BrikkTrait interface CleanEvent : RawEvent { val amount: BigDecimal? }
        interface CustomerRow : Shape { val tenant: String; val segment: String }
    """.trimIndent()

    fun testInheritedInputColumnsAndNullableTypesComeFromSharedHelper() {
        val host = configure("$traits\n@BrikkSql fun <T : CleanEvent> query(events: Rel<T>) = Sql.duckdb(\"FROM \$events() |> WHERE id > 0\")")
        val relations = BrikkRelationProvider.forFile(injected(host))
        val shape = (relations["events"] as ShapeResult.Available).shape
        assertEquals(ShapeKnowledge.PARTIAL, shape.knowledge)
        assertEquals(setOf("id", "tenant", "event_at", "amount"), shape.columns.map { it.name }.toSet())
        assertEquals(ColumnNullability.NULLABLE, shape.columns.single { it.name == "amount" }.nullability)
        assertEquals("java.time.Instant", shape.columns.single { it.name == "event_at" }.kotlinClassId)
    }
    fun testNativeSqlSlotColumnsResolveAndNavigateToTheKotlinProperty() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>) = Sql.duckdb(\"SELECT e.amount FROM \$events() e\")")
        val file = injected(host)
        val refs = PsiTreeUtil.findChildrenOfType(file, SqlReferenceExpression::class.java)
        val amount = refs.first { it.text == "e.amount" }
        val target = amount.reference.resolve()
        assertNotNull("Slot column must resolve through SQL resolver; refs=$refs", target)
        val navigation = target!!.navigationElement
        assertTrue(navigation.text, navigation.text.contains("amount"))
        assertEquals("Sample.kt", navigation.containingFile.name)
    }
    fun testInputTraitEditRefreshesWithoutEditingTheSqlString() {
        var host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>) = Sql.duckdb(\"SELECT id FROM \$events()\")")
        val file = injected(host)
        assertEquals(4, (BrikkRelationProvider.forFile(file)["events"] as ShapeResult.Available).shape.columns.size)
        WriteCommandAction.runWriteCommandAction(project) {
            val text = myFixture.editor.document.text
            val at = text.indexOf("val amount: BigDecimal?")
            myFixture.editor.document.insertString(at, "val region: String; ")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        host = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).last()
        val shape = (BrikkRelationProvider.forFile(injected(host))["events"] as ShapeResult.Available).shape
        assertTrue(shape.columns.any { it.name == "region" })
    }
    fun testPipeCompletionShowsContractColumnsAfterAFilter() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>) = Sql.duckdb(\"FROM \$events() |> WHERE am<caret>\")")
        val file = injected(host)
        myFixture.completeBasic()
        assertTrue("Expected amount completion, lookup=${myFixture.lookupElementStrings}; text=${myFixture.editor.document.text}",
            myFixture.lookupElementStrings.orEmpty().contains("amount") || myFixture.editor.document.text.contains("WHERE amount"))
        assertTrue(BrikkSqlEditorSupport.contracts(file).containsKey("events"))
    }
    fun testPlainNativeSqlCompletionUsesASlotAlias() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>) = Sql.duckdb(\"SELECT e.am<caret> FROM \$events() AS e\")")
        injected(host)
        myFixture.completeBasic()
        assertTrue("Expected qualified amount completion: ${myFixture.lookupElementStrings}",
            myFixture.lookupElementStrings.orEmpty().contains("amount") || myFixture.editor.document.text.contains("SELECT e.amount"))
    }
    fun testJoinScopesAndAmbiguousColumnsAreNotFlattenedToOneType() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>, customers: Rel<CustomerRow>) = " +
            "Sql.duckdb(\"SELECT e.tenant, c.segment FROM \$events() e LEFT JOIN \$customers() c ON e.tenant=c.tenant\")")
        val file = injected(host)
        val analysis = BrikkSqlEditorSupport.analysis(file)!!
        val ref = analysis.references.first { it.name == "segment" }
        assertEquals(ColumnNullability.NULLABLE, ref.scope.candidates("segment", "c").single().second.nullability)
        assertEquals(2, ref.scope.candidates("tenant").size)
    }
    fun testMissingFullAndPartialColumnsHaveDifferentDiagnostics() {
        val host = configure("$traits\n@BrikkSql fun query(customers: Rel<CustomerRow>, events: Rel<CleanEvent>) = " +
            "Sql.duckdb(\"SELECT c.missing, e.extra FROM \$customers() c JOIN \$events() e ON c.tenant=e.tenant\")")
        injected(host)
        myFixture.enableInspections(com.intellij.sql.inspections.SqlResolveInspection())
        val messages = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(messages.toString(), messages.any { it.contains("missing") && it.contains("full relation contract") })
        assertTrue(messages.toString(), messages.any { it.contains("extra") && it.contains("not guaranteed") })
    }
    fun testEqualColumnNamesOnTwoSlotsDoNotBecomeATautology() {
        val host = configure("$traits\n@BrikkSql fun query(left: Rel<CleanEvent>, right: Rel<CleanEvent>) = " +
            "Sql.duckdb(\"SELECT a.id FROM \$left() a JOIN \$right() b ON a.id=b.id\")")
        injected(host)
        myFixture.enableInspections(com.intellij.sql.inspections.SqlConstantExpressionInspection())
        val messages = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(messages.toString(), messages.any { it.contains("always", true) && it.contains("true", true) })
    }
    fun testIdenticalSlotNamesInDifferentFunctionsNeverShareAContract() {
        configure("""
            interface A : Partial { val first: Int }
            interface B : Partial { val second: String }
            @BrikkSql fun one(src: Rel<A>) = Sql.duckdb("SELECT first FROM ${'$'}src()")
            @BrikkSql fun two(src: Rel<B>) = Sql.duckdb("SELECT second FROM ${'$'}src()")
        """.trimIndent())
        val hosts = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java)
            .filter { it.text.contains("SELECT") }
        val columns = hosts.map { host -> (BrikkRelationProvider.forFile(injected(host))["src"] as ShapeResult.Available)
            .shape.columns.map { it.name } }
        assertEquals(listOf(listOf("first"), listOf("second")), columns)
    }
}
