package dev.sort.duckdb.brikk

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.intellij.*
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.junit.Assume
import java.nio.file.Path

/** Real compiler-generated shapes, opt-in and restricted to the reviewed exact IDE compiler. */
class BrikkCompilerRelationTest : BrikkRelationEditingTest() {
    private fun enableCompiler() {
        val jar = System.getProperty("test.brikkCompilerPlugin")
        Assume.assumeTrue("Generated fixtures need a reviewed compiler-plugin JAR", jar != null)
        assertEquals("Do not substitute a mismatched compiler artifact", "2.4.20-ij262-34",
            org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION)
        val point = com.intellij.openapi.application.ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider>(
                "org.jetbrains.kotlin.bundledFirCompilerPluginProvider")
        point.registerExtension(object : org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider {
            override fun provideBundledPluginJar(project: com.intellij.openapi.project.Project, userSuppliedPluginJar: Path): Path? =
                Path.of(jar!!).takeIf { it == userSuppliedPluginJar }
        }, testRootDisposable)
        val runtime = myFixture.findFileInTempDir("runtime/Types.kt")
        val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(runtime)!!
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psi)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(document.text.replace("interface Rel<T>", "class Rel<out T : Partial>")
                .replace("fun duckdb", "@BrikkSqlDialect(\"duckdb\") fun duckdb"))
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            val manager = com.intellij.facet.FacetManager.getInstance(module)
            val existing = org.jetbrains.kotlin.idea.facet.KotlinFacet.get(module)
            val facet = existing ?: manager.createFacet(org.jetbrains.kotlin.idea.facet.KotlinFacetType.INSTANCE, "Kotlin", null)
            facet.configuration.settings.useProjectSettings = false
            facet.configuration.settings.compilerArguments = org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments().apply {
                pluginClasspaths = arrayOf(jar!!)
            }
            if (existing == null) manager.createModifiableModel().apply { addFacet(facet); commit() }
        }
    }

    fun testActualGeneratedRowResolvesInsideTheDuckdbInjectionOnColdOpen() {
        enableCompiler()
        val host = configure("""
            @BrikkSql fun source() = Sql.duckdb("SELECT 1 AS id, 'x' AS code")
            @BrikkSql fun query(rows: Rel<SourceOut>) = Sql.duckdb("SELECT r.code FROM ${'$'}rows() AS r")
        """.trimIndent())
        val file = injected(host)
        val result = brikkContracts(file)["rows"]
        assertTrue(result.toString(), result is ShapeResult.Available)
        val shape = (result as ShapeResult.Available).shape
        assertEquals(ShapeKnowledge.FULL, shape.knowledge)
        assertEquals(setOf("id", "code"), shape.columns.map { it.name }.toSet())
        val reference = PsiTreeUtil.findChildrenOfType(file, SqlReferenceExpression::class.java).first { it.text == "r.code" }
        val analysis = brikkScope(file)
        assertNotNull("Generated column resolution: range=${reference.reference.rangeInElement}, " +
            "element=${reference.reference.element.text}, analysis=$analysis, variants=${reference.reference.multiResolve(false).toList()}",
            reference.reference.resolve())
        assertEquals(NavigationKind.RELATION_FALLBACK, shape.columns.single { it.name == "code" }.navigation!!.kind)
    }

    fun testActualRefinedCallHasExtraColumnsButGenericBodySeesOnlyItsBounds() {
        enableCompiler()
        val host = configure("""
            @BrikkTrait interface HasId : Partial { val id: Int }
            @BrikkSql fun source() = Sql.duckdb("SELECT 1 AS id, 'x' AS code")
            @BrikkSql fun <T : HasId> extend(src: Rel<T>) = Sql.duckdb("FROM ${'$'}src() |> EXTEND 2 AS added")
            fun use() { val refined = extend(source()); println(refined) }
        """.trimIndent())
        val genericShape = (brikkContracts(injected(host))["src"] as ShapeResult.Available).shape
        assertEquals(setOf("id"), genericShape.columns.map { it.name }.toSet())
        assertEquals(ShapeKnowledge.PARTIAL, genericShape.knowledge)
        val expression = PsiTreeUtil.findChildrenOfType(myFixture.file, KtCallExpression::class.java)
            .single { it.calleeExpression?.text == "extend" }
        val refined = BrikkRelationShapes.ofExpression(expression)
        assertTrue(refined.toString(), refined is ShapeResult.Available)
        assertEquals(setOf("id", "code", "added"), (refined as ShapeResult.Available).shape.columns.map { it.name }.toSet())
    }
}
