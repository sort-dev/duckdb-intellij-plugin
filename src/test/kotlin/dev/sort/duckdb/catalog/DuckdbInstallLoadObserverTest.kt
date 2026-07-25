package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.DuckdbDbms

/**
 * Pins the observer's ROUTING: an executed script earns the function-catalog refresh, the object
 * tree re-listing, both, or neither — and the two coalesce independently, so a script that installs
 * an extension and attaches through it in one run does not lose either refresh.
 *
 * The debouncer is driven by a fake scheduler (fires immediately) so the assertions are
 * deterministic; the platform-side refreshes are replaced by recording seams.
 */
class DuckdbInstallLoadObserverTest : BasePlatformTestCase() {

    private lateinit var catalogRefreshes: MutableList<String>
    private lateinit var treeRefreshes: MutableList<String>
    private lateinit var observer: DuckdbInstallLoadObserver

    override fun setUp() {
        super.setUp()
        catalogRefreshes = mutableListOf()
        treeRefreshes = mutableListOf()
        // Fire-immediately debouncer: coalescing still applies per key within one dispatch batch.
        val debouncer = DuckdbRefreshDebouncer(delayMillis = 0) { _, task -> task() }
        observer = DuckdbInstallLoadObserver(
            project = project,
            debouncer = debouncer,
            refresh = { _, ds -> catalogRefreshes.add(ds.name) },
            refreshTree = { _, ds -> treeRefreshes.add(ds.name) },
        )
    }

    private fun dataSource(name: String): LocalDataSource {
        val driver = DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native")
        assertNotNull("driversConfig must register duckdb-brikk-native", driver)
        val ds = LocalDataSource.create(name, "org.duckdb.DuckDBDriver", "jdbc:duckdb:", "")
        ds.databaseDriver = driver
        assertEquals(DuckdbDbms.DUCKDB_BRIKK, ds.dbms)
        return ds
    }

    fun testInstallRefreshesFunctionsOnly() {
        observer.dispatch(dataSource("duck"), "INSTALL spatial; LOAD spatial")
        assertEquals(listOf("duck"), catalogRefreshes)
        assertTrue("INSTALL/LOAD must not re-list namespaces", treeRefreshes.isEmpty())
    }

    fun testAttachRefreshesTreeOnly() {
        observer.dispatch(dataSource("duck"), "ATTACH 'side.duckdb' AS side")
        assertEquals(listOf("duck"), treeRefreshes)
        assertTrue("ATTACH must not re-harvest the function catalog", catalogRefreshes.isEmpty())
    }

    fun testDetachRefreshesTree() {
        observer.dispatch(dataSource("duck"), "DETACH side")
        assertEquals(listOf("duck"), treeRefreshes)
    }

    fun testInstallThenAttachInOneScriptRefreshesBoth() {
        observer.dispatch(dataSource("duck"), "INSTALL ducklake; ATTACH 'ducklake:m.db' AS lake")
        assertEquals("the function catalog must refresh", listOf("duck"), catalogRefreshes)
        assertEquals("and the tree must re-list — separate debounce keys", listOf("duck"), treeRefreshes)
    }

    fun testOrdinaryTrafficRefreshesNothing() {
        val ds = dataSource("duck")
        observer.dispatch(ds, "SELECT * FROM t")
        observer.dispatch(ds, "CREATE TABLE t(a INTEGER)")
        observer.dispatch(ds, "USE side")
        assertTrue(catalogRefreshes.isEmpty())
        assertTrue(treeRefreshes.isEmpty())
    }
}
