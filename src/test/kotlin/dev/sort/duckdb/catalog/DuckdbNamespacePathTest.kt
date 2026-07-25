package dev.sort.duckdb.catalog

import dev.sort.duckdb.catalog.DuckdbNamespacePath.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the completion-time targeting decision against a fake model — the DuckDB-specific part being
 * that a single segment can address either an ATTACHed CATALOG or a SCHEMA of the current catalog,
 * and that the schema-relative reading only applies when exactly one catalog offers that name.
 */
class DuckdbNamespacePathTest {

    private class FakeNode(
        override val name: String,
        private val children: MutableList<FakeNode> = mutableListOf(),
    ) : DuckdbNamespacePath.Node {
        override fun childNodes(): List<DuckdbNamespacePath.Node> = children
        fun with(vararg kids: FakeNode): FakeNode = apply { children.addAll(kids) }
    }

    /** primary catalog with a loaded `main` schema; `side` ATTACHed and still childless. */
    private fun model(): List<FakeNode> = listOf(
        FakeNode("app").with(FakeNode("main").with(FakeNode("orders"))),
        FakeNode("side"),
    )

    @Test
    fun `childless catalog deepens at CATALOG level`() {
        val deepen = DuckdbNamespacePath.decideDeepen(model(), listOf("side"))!!
        assertEquals(Level.CATALOG, deepen.level)
        assertEquals("side", deepen.catalog)
        assertNull(deepen.schema)
    }

    @Test
    fun `catalog with loaded schemas does not deepen`() =
        assertNull(DuckdbNamespacePath.decideDeepen(model(), listOf("app")))

    @Test
    fun `childless schema under a named catalog deepens at SCHEMA level`() {
        val roots = listOf(FakeNode("app").with(FakeNode("analytics")))
        val deepen = DuckdbNamespacePath.decideDeepen(roots, listOf("app", "analytics"))!!
        assertEquals(Level.SCHEMA, deepen.level)
        assertEquals("app", deepen.catalog)
        assertEquals("analytics", deepen.schema)
    }

    @Test
    fun `schema with loaded tables does not deepen`() =
        assertNull(DuckdbNamespacePath.decideDeepen(model(), listOf("app", "main")))

    @Test
    fun `bare schema resolves relative when exactly one catalog offers it`() {
        val roots = listOf(FakeNode("app").with(FakeNode("analytics")), FakeNode("side"))
        val deepen = DuckdbNamespacePath.decideDeepen(roots, listOf("analytics"))!!
        assertEquals(Level.SCHEMA, deepen.level)
        assertEquals("app", deepen.catalog)
        assertEquals("analytics", deepen.schema)
    }

    @Test
    fun `bare schema offered by two catalogs is ambiguous and deepens nothing`() {
        // The post-ATTACH reality: every DuckDB database has a `main` schema.
        val roots = listOf(
            FakeNode("app").with(FakeNode("main")),
            FakeNode("side").with(FakeNode("main")),
        )
        assertNull(DuckdbNamespacePath.decideDeepen(roots, listOf("main")))
    }

    @Test
    fun `catalog name wins over a same-named schema elsewhere`() {
        val roots = listOf(FakeNode("app").with(FakeNode("side")), FakeNode("side"))
        val deepen = DuckdbNamespacePath.decideDeepen(roots, listOf("side"))!!
        assertEquals(Level.CATALOG, deepen.level)
        assertEquals("side", deepen.catalog)
    }

    @Test
    fun `names come from the model not from what was typed`() {
        val roots = listOf(FakeNode("Side_DB"))
        val deepen = DuckdbNamespacePath.decideDeepen(roots, listOf("side_db"))!!
        assertEquals("Side_DB", deepen.catalog)
    }

    @Test
    fun `unresolved head deepens nothing`() =
        assertNull(DuckdbNamespacePath.decideDeepen(model(), listOf("sid")))

    @Test
    fun `unresolved second segment deepens nothing`() =
        assertNull(DuckdbNamespacePath.decideDeepen(model(), listOf("app", "nope")))

    @Test
    fun `three segments are left to the platform`() =
        assertNull(DuckdbNamespacePath.decideDeepen(model(), listOf("app", "main", "orders")))

    @Test
    fun `empty path deepens nothing`() =
        assertNull(DuckdbNamespacePath.decideDeepen(model(), emptyList()))

    @Test
    fun `empty model deepens nothing`() =
        assertNull(DuckdbNamespacePath.decideDeepen(emptyList(), listOf("side")))
}
