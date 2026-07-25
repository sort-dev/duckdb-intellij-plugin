package dev.sort.duckdb.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.Casing
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * OFFLINE assertions for the introspection-scope patterns the lazy deepening unions into a data
 * source. Model levels (PG family, Stage-5 truth battery): catalog = [ObjectKind.DATABASE],
 * schema = [ObjectKind.SCHEMA], and every ATTACHed database is its own catalog.
 */
class DuckdbCatalogScopesTest : BasePlatformTestCase() {

    private fun dbGroup(p: TreePattern): TreePatternNode.Group? = p.root?.getGroup(ObjectKind.DATABASE)

    private fun childMatching(group: TreePatternNode.Group?, name: String): TreePatternNode? =
        group?.children.orEmpty().firstOrNull { it.naming.matches(ObjectName.plain(name), Casing.EXACT) }

    fun testCatalogSchemasScopeIsABareCatalogLeaf() {
        // A selected DATABASE leaf loads that catalog's SCHEMAS (its direct children) and NO
        // tables. A SCHEMA(*) group here would instead select the schemas and pull every one of
        // their tables — which is exactly what must not happen behind `ATTACH 'postgres:…'`.
        val p = DuckdbCatalogScopes.catalogSchemasScope("lake")
        val lake = childMatching(dbGroup(p), "lake")
        assertNotNull("catalog deepening must name the catalog 'lake'", lake)
        assertFalse("a different catalog must NOT match", dbGroup(p)!!.children.orEmpty().any {
            it.naming.matches(ObjectName.plain("app"), Casing.EXACT)
        })
        assertNull(
            "catalog deepening must be a BARE DATABASE leaf (no SCHEMA group) so it never cascades to tables",
            lake!!.getGroup(ObjectKind.SCHEMA),
        )
    }

    fun testSchemaTablesScopeSelectsExactlyOneSchemaUnderCatalog() {
        val p = DuckdbCatalogScopes.schemaTablesScope("lake", "main")
        val lake = childMatching(dbGroup(p), "lake")
        assertNotNull("schema deepening must name the catalog 'lake'", lake)
        val schemaGroup = lake!!.getGroup(ObjectKind.SCHEMA)
        assertNotNull("must reach the SCHEMA level", schemaGroup)
        assertNotNull(
            "must name exactly the schema 'main'",
            schemaGroup!!.children.orEmpty().firstOrNull {
                it.naming.matches(ObjectName.plain("main"), Casing.EXACT)
            },
        )
        assertFalse(
            "must NOT select any other schema (targeted, not the whole catalog)",
            schemaGroup.children.orEmpty().any { it.naming.matches(ObjectName.plain("other"), Casing.EXACT) },
        )
    }

    fun testSchemaTablesScopeWithoutCatalogRootsSchemaGroupDirectly() {
        val p = DuckdbCatalogScopes.schemaTablesScope(null, "main")
        assertNull("no catalog was given -> no DATABASE group", dbGroup(p))
        val schemaGroup = p.root?.getGroup(ObjectKind.SCHEMA)
        assertNotNull("the SCHEMA group must be rooted directly", schemaGroup)
        assertTrue(
            "and it names 'main'",
            schemaGroup!!.children.orEmpty().any { it.naming.matches(ObjectName.plain("main"), Casing.EXACT) },
        )
    }
}
