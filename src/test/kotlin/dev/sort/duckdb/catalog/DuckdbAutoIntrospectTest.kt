package dev.sort.duckdb.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.Casing
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.catalog.DuckdbNamespacePath.Level

/**
 * OFFLINE assertions for the request orchestration's testable parts: the one-shot dedupe guard
 * ([DuckdbAutoIntrospect.claimOnce] + [DuckdbAutoIntrospect.keyFor]), the per-data-source claim
 * reset that an ATTACH/DETACH triggers ([DuckdbAutoIntrospect.forgetClaims]), and the level→scope
 * dispatch ([DuckdbAutoIntrospect.scopeFor]). The side-effectful `request(...)` (scope union +
 * platform refresh) needs a live data source and is left to the IDE bake.
 */
class DuckdbAutoIntrospectTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        DuckdbAutoIntrospect.resetForTest()
    }

    override fun tearDown() {
        try {
            DuckdbAutoIntrospect.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    fun testKeyDistinguishesLevelsCatalogsAndSchemas() {
        val ds = "ds-1"
        val catLake = DuckdbAutoIntrospect.keyFor(ds, Level.CATALOG, "lake", null)
        val catSide = DuckdbAutoIntrospect.keyFor(ds, Level.CATALOG, "side", null)
        val schLakeMain = DuckdbAutoIntrospect.keyFor(ds, Level.SCHEMA, "lake", "main")
        val schLakeRaw = DuckdbAutoIntrospect.keyFor(ds, Level.SCHEMA, "lake", "raw")

        assertFalse("different catalogs -> different keys", catLake == catSide)
        assertFalse("different schemas under the same catalog -> different keys", schLakeMain == schLakeRaw)
        assertFalse("a catalog deepen and a schema deepen are never the same key", catLake == schLakeMain)
        assertFalse(
            "the same namespace on two data sources is independent",
            catLake == DuckdbAutoIntrospect.keyFor("ds-2", Level.CATALOG, "lake", null),
        )
    }

    fun testClaimOnceIsTrueOnceThenFalse() {
        val k = "ds-1|CATALOG:lake"
        assertTrue("first claim must succeed", DuckdbAutoIntrospect.claimOnce(k))
        assertFalse("second claim of the same key must fail (one shot per session)", DuckdbAutoIntrospect.claimOnce(k))
        assertTrue("a different key is independent", DuckdbAutoIntrospect.claimOnce("ds-1|SCHEMA:lake.main"))
        DuckdbAutoIntrospect.resetForTest()
        assertTrue("after reset the key is claimable again", DuckdbAutoIntrospect.claimOnce(k))
    }

    fun testForgetClaimsClearsOnlyThatDataSource() {
        val mine = DuckdbAutoIntrospect.keyFor("ds-1", Level.CATALOG, "lake", null)
        val alsoMine = DuckdbAutoIntrospect.keyFor("ds-1", Level.SCHEMA, "lake", "main")
        val theirs = DuckdbAutoIntrospect.keyFor("ds-2", Level.CATALOG, "lake", null)
        assertTrue(DuckdbAutoIntrospect.claimOnce(mine))
        assertTrue(DuckdbAutoIntrospect.claimOnce(alsoMine))
        assertTrue(DuckdbAutoIntrospect.claimOnce(theirs))

        // An ATTACH/DETACH on ds-1 changed the catalog list: every ds-1 namespace may deepen again.
        DuckdbAutoIntrospect.forgetClaims("ds-1")

        assertTrue("ds-1 catalog claim must be re-claimable", DuckdbAutoIntrospect.claimOnce(mine))
        assertTrue("ds-1 schema claim must be re-claimable", DuckdbAutoIntrospect.claimOnce(alsoMine))
        assertFalse("another data source's claims must survive", DuckdbAutoIntrospect.claimOnce(theirs))
    }

    fun testScopeForCatalogLevelIsABareCatalogLeaf() {
        val p = DuckdbAutoIntrospect.scopeFor(Level.CATALOG, "lake", null)
        val lake = p.root?.getGroup(ObjectKind.DATABASE)?.children.orEmpty()
            .firstOrNull { it.naming.matches(ObjectName.plain("lake"), Casing.EXACT) }
        assertNotNull("CATALOG level must produce a catalog-leaf scope naming 'lake'", lake)
        assertNull("catalog deepen must NOT carry a SCHEMA group", lake!!.getGroup(ObjectKind.SCHEMA))
    }

    fun testScopeForSchemaLevelReachesTheSchema() {
        val p = DuckdbAutoIntrospect.scopeFor(Level.SCHEMA, "lake", "main")
        val lake = p.root?.getGroup(ObjectKind.DATABASE)?.children.orEmpty()
            .firstOrNull { it.naming.matches(ObjectName.plain("lake"), Casing.EXACT) }
        assertNotNull("SCHEMA level must still name the catalog", lake)
        val schemaGroup = lake!!.getGroup(ObjectKind.SCHEMA)
        assertNotNull("SCHEMA level must reach the schema level", schemaGroup)
        assertTrue(
            "and name exactly 'main'",
            schemaGroup!!.children.orEmpty().any { it.naming.matches(ObjectName.plain("main"), Casing.EXACT) },
        )
    }
}
