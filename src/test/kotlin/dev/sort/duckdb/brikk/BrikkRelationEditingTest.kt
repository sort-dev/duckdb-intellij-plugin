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
        val relations = brikkContracts(injected(host))
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
        assertEquals(4, (brikkContracts(file)["events"] as ShapeResult.Available).shape.columns.size)
        WriteCommandAction.runWriteCommandAction(project) {
            val text = myFixture.editor.document.text
            val at = text.indexOf("val amount: BigDecimal?")
            myFixture.editor.document.insertString(at, "val region: String; ")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        host = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).last()
        val shape = (brikkContracts(injected(host))["events"] as ShapeResult.Available).shape
        assertTrue(shape.columns.any { it.name == "region" })
    }
    fun testPipeCompletionShowsContractColumnsAfterAFilter() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>) = Sql.duckdb(\"FROM \$events() |> WHERE am<caret>\")")
        val file = injected(host)
        myFixture.completeBasic()
        assertTrue("Expected amount completion, lookup=${myFixture.lookupElementStrings}; text=${myFixture.editor.document.text}",
            myFixture.lookupElementStrings.orEmpty().contains("amount") || myFixture.editor.document.text.contains("WHERE amount"))
        assertTrue(brikkContracts(file).containsKey("events"))
    }
    fun testOfflinePipeAliasesStillCompleteInBrikkFiles() {
        val host = configure("@BrikkSql fun query() = Sql.duckdb(\"FROM range(3) |> SELECT range AS my_output |> WHERE my_ou<caret>\")")
        assertNotNull(BrikkDuckdbProfile.forFile(injected(host)))
        myFixture.completeBasic()
        assertTrue("Lookups: ${myFixture.lookupElementStrings}; text=${myFixture.editor.document.text}",
            myFixture.lookupElementStrings.orEmpty().contains("my_output") || myFixture.editor.document.text.contains("WHERE my_output"))
    }
    fun testPlainNativeSqlCompletionUsesASlotAlias() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>) = Sql.duckdb(\"SELECT e.am<caret> FROM \$events() AS e\")")
        injected(host)
        myFixture.completeBasic()
        assertTrue("Expected qualified amount completion: ${myFixture.lookupElementStrings}",
            myFixture.lookupElementStrings.orEmpty().contains("amount") || myFixture.editor.document.text.contains("SELECT e.amount"))
    }

    fun testOptionalSlotTypesDoNotReplaceStockScalarTyping() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>) = " +
            "Sql.duckdb(\"SELECT lower('X'), e.id FROM \$events() AS e\")")
        val file = injected(host)
        val calls = PsiTreeUtil.findChildrenOfType(file, com.intellij.sql.psi.SqlFunctionCallExpression::class.java)
        val slot = calls.single { it.nameElement?.name == "events" }
        val scalar = calls.single { it.nameElement?.name == "lower" }
        assertNotNull(dev.brikk.house.sql.intellij.relation.RelationTableTypes.of(slot, DuckdbRelationProfile))
        assertEquals("com.intellij.sql.psi.impl.SqlFunctionCallExpressionImpl", scalar.javaClass.name)
        assertFalse(scalar.dasType is com.intellij.sql.psi.SqlTableType)
        assertEquals("text", scalar.dasType.specification)
    }
    fun testJoinScopesAndAmbiguousColumnsAreNotFlattenedToOneType() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>, customers: Rel<CustomerRow>) = " +
            "Sql.duckdb(\"SELECT tenant, c.segment FROM \$events() e LEFT JOIN \$customers() c ON e.tenant=c.tenant\")")
        val file = injected(host)
        val analysis = brikkScope(file)
        val ref = analysis.references.first { it.name == "segment" }
        assertEquals(dev.brikk.house.sql.scope.Nullability.NULLABLE, ref.columns.single().nullability)
        assertEquals(2, analysis.references.first { it.name == "tenant" }.columns.size)
    }

    fun testRichColumnPresentationFromTheRealContributor() {
        val host = configure("$traits\n" +
            "interface DisplayRow : Shape { val payload: String?; val event_at: Instant }\n" +
            "@BrikkSql fun query(events: Rel<DisplayRow>) = Sql.duckdb(\"SELECT src.<caret> FROM \$events() AS src\")")
        injected(host)
        val items = myFixture.completeBasic().orEmpty()
        for ((name, type) in listOf("payload" to "String?", "event_at" to "Instant")) {
            val presentations = items.filter { it.lookupString == name }.map {
                com.intellij.codeInsight.lookup.LookupElementPresentation().also { shown -> it.renderElement(shown) }
            }
            assertTrue("Expected $name / $type / brikk column (src), got $presentations",
                presentations.any { it.typeText == type && it.tailText == "  brikk column (src)" })
        }
    }

    fun testRenamedLineageKeepsBricksAndComputedColumnsUseColumnIcon() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>) = " +
            "Sql.duckdb(\"FROM \$events() |> SELECT id AS renamed, 1 AS computed |> WHERE <caret>\")")
        injected(host)
        val items = myFixture.completeBasic().orEmpty()
        val renamed = items.single { it.lookupString == "renamed" }
        val computed = items.single { it.lookupString == "computed" }
        val declared = com.intellij.codeInsight.lookup.LookupElementPresentation().also(renamed::renderElement)
        val calculated = com.intellij.codeInsight.lookup.LookupElementPresentation().also(computed::renderElement)
        assertEquals("  brikk column (events)", declared.tailText)
        assertEquals("Long", declared.typeText)
        assertEquals(dev.brikk.house.sql.intellij.completion.SqlLookupIcons.BrikkColumn, declared.icon)
        assertTrue(calculated.tailText.orEmpty(), calculated.tailText.orEmpty().contains("computed column"))
        assertEquals(dev.brikk.house.sql.intellij.completion.SqlLookupStyles.DEFAULT[dev.brikk.house.sql.intellij.completion.SqlLookupKind.RELATION_COLUMN].icon, calculated.icon)
    }

    fun testSameNamedColumnsFromJoinAliasesRemainTwoSuggestions() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>, customers: Rel<CleanEvent>) = " +
            "Sql.duckdb(\"FROM \$events() AS a |> JOIN \$customers() AS b ON a.id=b.id |> SELECT i<caret>\")")
        injected(host)
        val items = myFixture.completeBasic().orEmpty().filter { it.lookupString == "id" }
        val columns = items.filter {
            val shown = com.intellij.codeInsight.lookup.LookupElementPresentation().also { p -> it.renderElement(p) }
            shown.tailText in setOf("  brikk column (a)", "  brikk column (b)")
        }
        assertEquals("Join id suggestions: $items", 2, columns.size)
        assertEquals(2, columns.map { it.`object` }.toSet().size)
        assertTrue(columns.all { it.lookupString == "id" })
    }

    fun testSelectListOffersBrikkColumnsAlongsideMetadataFunctionsAndKeywords() {
        val host = configure("$traits\n@BrikkSql fun query(events: Rel<CleanEvent>) = " +
            "Sql.duckdb(\"SELECT a<caret> FROM \$events()\")")
        injected(host)
        val names = myFixture.completeBasic().orEmpty().map { it.lookupString }
        assertTrue(names.toString(), names.containsAll(listOf("amount", "abs", "ABORT")))
    }
    fun testMissingFullAndPartialColumnsHaveDifferentDiagnostics() {
        val host = configure("$traits\n@BrikkSql fun query(customers: Rel<CustomerRow>, events: Rel<CleanEvent>) = " +
            "Sql.duckdb(\"SELECT c.missing, e.extra FROM \$customers() c JOIN \$events() e ON c.tenant=e.tenant\")")
        injected(host)
        myFixture.enableInspections(dev.sort.duckdb.registeredSqlInspection("SqlResolveInspection"))
        val messages = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(messages.toString(), messages.any { it == "Unknown column 'missing'" })
        assertTrue(messages.toString(), messages.any { it == "Column 'extra' is not in the partial relation contract" })
    }
    fun testEqualColumnNamesOnTwoSlotsDoNotBecomeATautology() {
        val host = configure("$traits\n@BrikkSql fun query(left: Rel<CleanEvent>, right: Rel<CleanEvent>) = " +
            "Sql.duckdb(\"SELECT a.id FROM \$left() a JOIN \$right() b ON a.id=b.id\")")
        injected(host)
        myFixture.enableInspections(dev.sort.duckdb.registeredSqlInspection("SqlConstantExpressionInspection"))
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
        val columns = hosts.map { host -> (brikkContracts(injected(host))["src"] as ShapeResult.Available)
            .shape.columns.map { it.name } }
        assertEquals(listOf(listOf("first"), listOf("second")), columns)
    }
}
