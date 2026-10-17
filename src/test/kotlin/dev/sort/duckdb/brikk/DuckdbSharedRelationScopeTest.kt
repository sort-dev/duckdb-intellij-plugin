package dev.sort.duckdb.brikk

import dev.brikk.house.sql.scope.*
import org.junit.Assert.*
import org.junit.Test

/** DuckDB contracts now exercise core's analyzer, not a second plugin-owned implementation. */
class DuckdbSharedRelationScopeTest {
    private val facts = RelationFacts(slots = mapOf(
        "events" to RelationFact(listOf(ColumnFact("id", displayType = "Int", nullability = Nullability.NON_NULL,
            origin = ColumnOrigin.DECLARED, provenance = "events.id")), Knowledge.PARTIAL),
        "customers" to RelationFact(listOf(ColumnFact("id", nullability = Nullability.NON_NULL), ColumnFact("segment", nullability = Nullability.NON_NULL)), Knowledge.FULL),
    ))
    private fun analyze(sql: String) = RelationScope.analyze(sql, "duckdb", facts)
    @Test fun aliasesHideOriginalSlotNames() {
        val scope = analyze("SELECT e.id, events.id FROM events() e")
        assertEquals(Resolution.RESOLVED, scope.references.first { it.name == "id" }.resolution)
        assertTrue(scope.references.any { it.name == "events" && it.resolution == Resolution.UNRESOLVED })
    }
    @Test fun joinsKeepAmbiguityAndNullableSides() {
        for (side in listOf("LEFT", "RIGHT", "FULL")) {
            val scope = analyze("SELECT e.id, c.segment FROM events() e $side JOIN customers() c ON e.id=c.id")
            assertEquals(if (side == "LEFT") Nullability.NON_NULL else Nullability.NULLABLE,
                scope.references.first { it.name == "id" }.columns.single().nullability)
            assertEquals(if (side == "RIGHT") Nullability.NON_NULL else Nullability.NULLABLE,
                scope.references.first { it.name == "segment" }.columns.single().nullability)
        }
        assertTrue(analyze("SELECT id FROM events() e JOIN customers() c ON e.id=c.id").diagnostics.any { it.problem == ScopeProblem.AMBIGUOUS_COLUMN })
    }
    @Test fun pipeProjectionDropsInputsAndKeepsRenamedLineage() {
        val scope = analyze("FROM events() |> SELECT id AS renamed |> WHERE renamed > 0")
        val renamed = scope.references.last { it.name == "renamed" }.columns.single()
        assertEquals("events.id", renamed.provenance)
        assertEquals(ColumnOrigin.DECLARED, renamed.origin)
    }
    @Test fun aggregateOrderFollowsOutputOrder() {
        val sql = "FROM events() |> AGGREGATE COUNT(*) AS n GROUP BY id |> ORDER BY "
        assertEquals(listOf("id", "n"), RelationScope.complete(sql, "duckdb", facts, sql.length).items.map { it.name })
    }
    @Test fun physicalTablesAndCallsWithArgumentsAreNotSlots() {
        for (call in listOf("events", "events(1)", "db.events()")) {
            assertTrue(analyze("SELECT e.id FROM $call e").references.first { it.name == "id" }.columns.isEmpty())
        }
    }
    @Test fun partialMissingColumnsAreWarningsNotErrors() {
        val scope = analyze("FROM events() |> SELECT * |> WHERE missing > 0")
        assertTrue(scope.diagnostics.any { it.problem == ScopeProblem.PARTIAL_CONTRACT })
        assertFalse(scope.diagnostics.any { it.problem == ScopeProblem.MISSING_COLUMN })
    }
    @Test fun reservedRowsSlotParsesWithoutPluginFallback() {
        val sql = "SELECT r.id FROM rows() r"
        val rows = RelationScope.analyze(sql, "duckdb", facts.copy(slots = mapOf("rows" to facts.slots.getValue("events"))))
        assertTrue(rows.parsed)
        assertEquals(sql.indexOf("id"), rows.references.first { it.name == "id" }.start)
        assertEquals(Resolution.RESOLVED, rows.references.first { it.name == "id" }.resolution)
    }
}
