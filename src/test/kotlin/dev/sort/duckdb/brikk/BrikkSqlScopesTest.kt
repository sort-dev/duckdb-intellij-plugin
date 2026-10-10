package dev.sort.duckdb.brikk

import dev.brikk.house.intellij.*
import org.junit.Assert.*
import org.junit.Test

class BrikkSqlScopesTest {
    private fun column(name: String, type: String = "kotlin.Int", nullable: ColumnNullability = ColumnNullability.NON_NULL) =
        ColumnContract(name, type, nullable, BrikkTypeFamilies.forClassId(type))
    private fun shape(full: Boolean, vararg columns: ColumnContract): ShapeResult = ShapeResult.Available(
        RelationShape(if (full) ShapeKnowledge.FULL else ShapeKnowledge.PARTIAL, columns.toList(), ShapeOrigin.DECLARED))
    private val contracts = mapOf("events" to shape(false, column("id"), column("amount", "java.math.BigDecimal", ColumnNullability.NULLABLE)),
        "customers" to shape(true, column("id"), column("segment", "kotlin.String")))
    private fun reference(sql: String, fragment: String, map: Map<String, ShapeResult> = contracts): BrikkSqlScopes.Reference {
        val analysis = BrikkSqlScopes.analyze(sql, map)
        assertNotNull("$sql", analysis)
        val offset = sql.lastIndexOf(fragment) + fragment.indexOf('.') + 1
        return analysis!!.at(offset) ?: error("No reference at $offset for $fragment: ${analysis.references}")
    }
    @Test fun aliasesAreLocalAndHideTheOriginalSlotName() {
        val sql = "SELECT e.id, events.id FROM events() AS e"
        val ref = reference(sql, "e.id")
        assertEquals(1, ref.scope.candidates("id", "e").size)
        assertNull(ref.scope.relation("events"))
    }
    @Test fun outerJoinMakesOnlyNullableSidesNullable() {
        for ((side, eNull, cNull) in listOf(Triple("LEFT", false, true), Triple("RIGHT", true, false), Triple("FULL", true, true))) {
            val sql = "SELECT e.id, c.segment FROM events() e $side JOIN customers() c ON e.id = c.id"
            val ref = reference(sql, "c.segment")
            assertEquals(if (eNull) ColumnNullability.NULLABLE else ColumnNullability.NON_NULL, ref.scope.candidates("id", "e").single().second.nullability)
            assertEquals(if (cNull) ColumnNullability.NULLABLE else ColumnNullability.NON_NULL, ref.scope.candidates("segment", "c").single().second.nullability)
        }
    }
    @Test fun unqualifiedJoinColumnsRemainAmbiguous() {
        val sql = "SELECT id FROM events() e JOIN customers() c ON e.id=c.id"
        val ref = reference(sql, "SELECT id".substringAfter("SELECT "))
        assertEquals(2, ref.scope.candidates("id").size)
    }
    @Test fun pipeScopesEvolveAndNeverOfferFutureOrDroppedColumns() {
        val sql = "FROM events() |> WHERE id > 0 |> EXTEND id AS added |> SELECT added |> ORDER BY added"
        val before = reference(sql, "id >")
        assertTrue(before.scope.candidates("added").isEmpty())
        val after = reference(sql, "ORDER BY added".substringAfter("ORDER BY "))
        assertEquals(listOf("added"), after.scope.columns.map { it.second.name })
        assertTrue(after.scope.candidates("id").isEmpty())
    }
    @Test fun aggregateRetainsGroupingKeysAndEnumeratesOutputs() {
        val sql = "FROM events() |> AGGREGATE COUNT(*) AS n GROUP BY id |> ORDER BY id, n"
        val ref = reference(sql, "n")
        assertEquals(setOf("id", "n"), ref.scope.columns.map { it.second.name }.toSet())
    }
    @Test fun ctesAndSubqueriesHaveIndependentAliasesAndProjectedInventories() {
        val sql = "WITH e AS (SELECT id FROM events()) SELECT c.id FROM (SELECT id FROM customers()) c"
        val ref = reference(sql, "c.id")
        assertEquals(listOf("id"), ref.scope.columns.map { it.second.name })
        assertNull(ref.scope.relation("e"))
    }
    @Test fun physicalTablesWithTheSameNameAreNotKotlinSlots() {
        val sql = "SELECT events.id FROM events"
        val ref = reference(sql, "events.id")
        assertFalse(ref.scope.managed)
        assertTrue(ref.scope.columns.isEmpty())
    }
    @Test fun unknownShapesStayUnknownAndPartialStarsStayOpen() {
        val sql = "FROM events() |> SELECT * |> WHERE extra > 1"
        val partial = reference(sql, "extra")
        assertFalse(partial.scope.complete)
        val unknown = reference(sql, "extra", mapOf("events" to ShapeResult.Unavailable("pending")))
        assertFalse(unknown.scope.relations.single().available)
        assertTrue(unknown.scope.columns.isEmpty())
    }
    @Test fun pipeJoinAndAsUseTheirOwnScope() {
        val sql = "FROM events() |> AS e |> LEFT JOIN customers() AS c ON e.id=c.id |> WHERE c.segment IS NULL"
        val ref = reference(sql, "c.segment")
        assertEquals(ColumnNullability.NULLABLE, ref.scope.candidates("segment", "c").single().second.nullability)
    }
    @Test fun extendPreservesInputNamespacesAndPartialKnowledge() {
        val sql = "FROM events() AS e |> EXTEND e.id AS next |> WHERE e.id = next"
        val ref = reference(sql, "e.id")
        assertEquals(1, ref.scope.candidates("id", "e").size)
        assertEquals(1, ref.scope.candidates("next").size)
        assertFalse(ref.scope.complete)
    }
    @Test fun reservedCalledSlotNamesKeepOriginalOffsetsAndPhysicalSqlIsUntouched() {
        val sql = "SELECT r.id FROM rows() AS r"
        val ref = reference(sql, "r.id", mapOf("rows" to shape(true, column("id"))))
        assertEquals(1, ref.scope.candidates("id", "r").size)
        assertEquals(sql.indexOf("id"), ref.start)
        assertNotNull(BrikkSqlScopes.analyze("SELECT 'rows' AS label FROM events()", contracts))
    }
}
