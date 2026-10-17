package dev.sort.duckdb.catalog

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.types.DasSimpleTypeClass
import com.intellij.database.types.DasType
import com.intellij.openapi.components.service
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.duckdb.DuckdbDbms
import dev.sort.duckdb.pipes.DuckdbPipesSettings
import dev.sort.duckdb.sql.DuckdbSqlDialect

class DuckdbPhysicalRelationTest : DuckdbCatalogTestCase() {
    fun testPhysicalTableColumnsCompleteInsidePipeWithSqlTypes() {
        val model = ModelFactory.BLACK_HOLE.createModel(DuckdbDbms.DUCKDB_BRIKK)
        val table = (model.root as PgRoot).databases.createOrGet("memory").schemas.createOrGet("main").tables.createOrGet("orders")
        table.columns.createOrGet("amount").storedType = object : DasType {
            override fun getTypeClass() = DasSimpleTypeClass("DECIMAL(10,2)")
            override fun getDescription() = "DECIMAL(10,2)"
            override fun getSpecification() = "DECIMAL(10,2)"
            override fun toDataType() = com.intellij.database.model.DataType.UNKNOWN
        }
        source().model = model
        DbPsiFacade.getInstance(project).clearCaches()
        setSqlDialectMapping(project, null, DuckdbSqlDialect)
        val settings = project.service<DuckdbPipesSettings>()
        val previous = settings.enabled
        try {
            settings.enabled = true
            myFixture.configureByText("physical-pipe.sql", "FROM memory.main.orders |> WHERE am<caret>")
            val columns = myFixture.completeBasic().orEmpty().filter { it.lookupString == "amount" }
            assertEquals(columns.toString(), 1, columns.size)
            val shown = LookupElementPresentation().also(columns.single()::renderElement)
            assertEquals("  column (orders)", shown.tailText)
            assertTrue(shown.typeText.orEmpty(), shown.typeText.orEmpty().contains("DECIMAL", ignoreCase = true))
            assertFalse(shown.typeText.orEmpty(), '?' in shown.typeText.orEmpty())
            assertFalse(shown.typeText.orEmpty(), shown.typeText.orEmpty().contains("nullability", ignoreCase = true))
        } finally {
            LookupManager.getInstance(project).hideActiveLookup()
            settings.enabled = previous
        }
    }
}
