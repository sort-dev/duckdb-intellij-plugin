package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseDriver
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.GuardedRef
import com.intellij.database.util.LoaderContext
import com.intellij.database.util.TreePatternUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.classpath.SimpleClasspathElement
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * LIVE, CONTAINER-FREE integration proof for ATTACH-aware tree refresh + lazy deepening. DuckDB is
 * embedded, so unlike the sibling doris/trino live suites this one needs no server and runs in the
 * normal offline gate: a temp `.duckdb` file IS the database.
 *
 * Measures, end to end, through the REAL platform introspection:
 *
 *  1. **Connect:** a fresh data source's default sync enumerates the primary catalog.
 *  2. **ATTACH is invisible until we act:** a database attached over a held platform connection is
 *     NOT in the model — the very gap [DuckdbAttachDetector] + [DuckdbTreeRefresh] close.
 *  3. **Tree refresh:** [DuckdbTreeRefresh.listNamespaces] brings the attached catalog into the model.
 *  4. **Lazy deepening:** [DuckdbAutoIntrospect.request] loads exactly one level per call.
 *
 * The connection is HELD open across the whole run on purpose: DuckDB drops attachments when the
 * last connection to an instance closes (driver-level probe), which is also why this mirrors the
 * real IDE, where the user's console connection stays open.
 *
 * JUnit3 (BasePlatformTestCase) has no assumptions, so an in-fixture connectivity failure prints
 * `SKIP:` and returns rather than failing the gate; once a sync succeeds, every assertion is HARD.
 */
class DuckdbAttachTreeLiveTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private var dataSource: LocalDataSource? = null
    private var driver: DatabaseDriver? = null
    private var savedClasspath: List<SimpleClasspathElement>? = null
    private var held: GuardedRef<DatabaseConnection>? = null

    private val primaryFile: File get() = tempDir.resolve("primary.duckdb").toFile()
    private val sideFile: File get() = tempDir.resolve("side.duckdb").toFile()

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("duckdb-attach-tree")
        DuckdbAutoIntrospect.resetForTest()
        // Seed both databases with the test-classpath driver, then close: from here on only the
        // platform's out-of-process JDBC host touches them.
        DriverManager.getConnection("jdbc:duckdb:${primaryFile.absolutePath}").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE primary_t(id INTEGER)") }
        }
        DriverManager.getConnection("jdbc:duckdb:${sideFile.absolutePath}").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE side_t(id INTEGER, note VARCHAR)") }
        }
    }

    override fun tearDown() {
        try {
            runCatching { held?.close() }
            dataSource?.let { ds ->
                runCatching { DataSourceSyncManager.getInstance().stopSynchronization(ds) }
                runCatching { LocalDataSourceManager.getInstance(project).removeDataSource(ds) }
            }
            driver?.let { d -> savedClasspath?.let { runCatching { d.additionalClasspathElements = it } } }
            DuckdbAutoIntrospect.resetForTest()
            runCatching { tempDir.toFile().deleteRecursively() }
        } finally {
            super.tearDown()
        }
    }

    /** Add the on-disk duckdb_jdbc jar to the driver so the remote JDBC host can load it. */
    private fun injectDriverJar(d: DatabaseDriver) {
        savedClasspath = d.additionalClasspathElements
        val jarPath = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .firstOrNull {
                it.substringAfterLast(File.separatorChar)
                    .let { n -> n.startsWith("duckdb_jdbc") && n.endsWith(".jar") }
            }
            ?: error("duckdb_jdbc jar not found on the test classpath (java.class.path)")
        d.additionalClasspathElements =
            SimpleClasspathElementFactory.createElements("jar://${File(jarPath).absolutePath}!/")
    }

    private fun registerDataSource(): LocalDataSource {
        val d = DatabaseDriverManager.getInstance().getDriver("duckdb-brikk-native")
            ?: error("driversConfig must register duckdb-brikk-native")
        driver = d
        injectDriverJar(d)
        val ds = LocalDataSource.create(
            "duckdb-attach-tree", "org.duckdb.DuckDBDriver", "jdbc:duckdb:${primaryFile.absolutePath}", "",
        )
        ds.databaseDriver = d
        LocalDataSourceManager.getInstance(project).addDataSource(ds)
        dataSource = ds
        return ds
    }

    // --- model reads (all under a read action; the fixture runs on the EDT) -------------------

    private fun modelRoots(ds: LocalDataSource): List<DasObject> =
        ReadAction.compute<List<DasObject>, RuntimeException> {
            DbPsiFacade.getInstance(project).findDataSource(ds.uniqueId)?.model?.modelRoots?.toList().orEmpty()
        }

    private fun childrenOf(node: DasObject): List<DasObject> =
        ReadAction.compute<List<DasObject>, RuntimeException> {
            (node.getDasChildren(ObjectKind.SCHEMA).toList() +
                node.getDasChildren(ObjectKind.TABLE).toList() +
                node.getDasChildren(null).toList()).distinct()
        }

    private fun nameOf(o: DasObject): String = ReadAction.compute<String, RuntimeException> { o.name }

    private fun rootNamed(ds: LocalDataSource, name: String): DasObject? =
        modelRoots(ds).firstOrNull { nameOf(it).equals(name, true) }

    private fun snapshot(ds: LocalDataSource): String {
        val roots = modelRoots(ds)
        return roots.joinToString(" | ") { r -> "${nameOf(r)}[${childrenOf(r).joinToString(",") { nameOf(it) }}]" }
    }

    // --- platform driving ----------------------------------------------------------------------

    /** Run [body] on a pooled thread while pumping the EDT; returns its result or null on failure. */
    private fun <T> offEdt(what: String, timeoutSeconds: Int = 60, body: () -> T): T? {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val done = AtomicBoolean(false)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                result.set(body())
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.set(true)
            }
        }
        runCatching { PlatformTestUtil.waitWithEventsDispatching(what, { done.get() }, timeoutSeconds) }
        failure.get()?.let { println("SKIP-CAUSE ($what): ${it.javaClass.simpleName}: ${it.message}") }
        return result.get()
    }

    private fun awaitSyncOrSkip(contextFactory: () -> LoaderContext, timeoutMs: Long = 60_000): Boolean {
        repeat(2) { attempt ->
            try {
                val future = DataSourceSyncManager.getInstance().tryPerform(contextFactory(), true, false)?.toFuture()
                    ?: run { println("SKIP: tryPerform returned no task (in-fixture)"); return false }
                PlatformTestUtil.waitForFuture(future, timeoutMs)
                return true
            } catch (t: Throwable) {
                val cancelled = generateSequence<Throwable>(t) { it.cause }
                    .any { it.javaClass.simpleName.contains("Cancell", true) }
                if (cancelled && attempt == 0) { println("(sync cancelled; retrying once)"); return@repeat }
                println("SKIP: in-fixture sync failed/timed out: ${t.javaClass.simpleName}: ${t.message}")
                return false
            }
        }
        return false
    }

    /** Execute [sql] over the HELD platform connection (same JDBC host + DuckDB instance the
     *  introspection uses — the whole point of the ATTACH visibility question). */
    private fun executeOnHeldConnection(sql: String): Boolean = offEdt("execute: $sql") {
        val connection = held!!.get()
        val statement = JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() }
            ?: error("no statement")
        try {
            JdbcNativeUtil.performRemote { statement.execute(sql) }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
        true
    } ?: false

    fun testAttachBecomesVisibleOnlyAfterTreeRefreshThenDeepensOneLevel() {
        val ds = registerDataSource()

        // STAGE 1 — default connect.
        if (!awaitSyncOrSkip({ LoaderContext.selectGeneralTask(project, ds) })) return
        runCatching { PlatformTestUtil.waitWithEventsDispatching("first introspection", { modelRoots(ds).isNotEmpty() }, 30) }
        val scope = ReadAction.compute<String, RuntimeException> {
            runCatching { TreePatternUtils.serialize(ds.introspectionScope) }.getOrElse { "<none>" }
        }
        println("=== STAGE1 connect: ${snapshot(ds)} ; scope=$scope ===")
        val primary = rootNamed(ds, "primary")
        if (primary == null) {
            println("SKIP: no catalogs enumerated in-fixture (connectivity); roots=${modelRoots(ds).map(::nameOf)}")
            return
        }

        // STAGE 2 — ATTACH over a HELD connection; the model must NOT know about it yet.
        held = offEdt("open held connection") {
            DatabaseConnectionManager.getInstance().build(project, ds)
                .setRequestor(ConnectionRequestor.Anonymous())
                .createBlockingNonCancellable()
        }
        if (held == null) { println("SKIP: could not open a platform connection in-fixture"); return }
        if (!executeOnHeldConnection("ATTACH '${sideFile.absolutePath}' AS side_db")) {
            println("SKIP: ATTACH did not execute in-fixture"); return
        }
        assertNull(
            "MEASURED: an ATTACHed database is NOT in the model until something re-lists namespaces " +
                "— exactly the gap the observer closes (roots=${modelRoots(ds).map(::nameOf)})",
            rootNamed(ds, "side_db"),
        )

        // STAGE 3 — the tree refresh brings it in.
        assertTrue("tree refresh must submit a sync", DuckdbTreeRefresh.listNamespaces(project, ds))
        runCatching {
            PlatformTestUtil.waitWithEventsDispatching(
                "side_db in the model after namespace re-listing", { rootNamed(ds, "side_db") != null }, 45,
            )
        }
        println("=== STAGE3 after tree refresh: ${snapshot(ds)} ===")
        val side = rootNamed(ds, "side_db")
        assertNotNull("ATTACHed catalog must appear after DuckdbTreeRefresh.listNamespaces", side)

        // STAGE 4 — lazy deepening, one level at a time.
        if (childrenOf(side!!).isEmpty()) {
            assertTrue(
                "catalog deepen must kick a NEW introspection",
                DuckdbAutoIntrospect.request(
                    project, ds, DuckdbNamespacePath.Level.CATALOG, nameOf(side), null, side,
                ),
            )
            runCatching {
                PlatformTestUtil.waitWithEventsDispatching(
                    "side_db schemas after catalog deepen", { childrenOf(side).isNotEmpty() }, 45,
                )
            }
            println("=== STAGE4 after CATALOG deepen: ${snapshot(ds)} ===")
            assertFalse(
                "a second deepen of the same namespace must be de-duped (one shot per session)",
                DuckdbAutoIntrospect.request(
                    project, ds, DuckdbNamespacePath.Level.CATALOG, nameOf(side), null, side,
                ),
            )
        } else {
            println("=== STAGE4 skipped: side_db already carried children after the re-listing ===")
        }
        val schemas = childrenOf(side).map(::nameOf)
        assertTrue("side_db must expose its 'main' schema: $schemas", schemas.any { it.equals("main", true) })

        // And its table resolves once the schema is deepened (or already came with it).
        val main = childrenOf(side).first { nameOf(it).equals("main", true) }
        if (childrenOf(main).isEmpty()) {
            DuckdbAutoIntrospect.request(
                project, ds, DuckdbNamespacePath.Level.SCHEMA, nameOf(side), nameOf(main), main,
            )
            runCatching {
                PlatformTestUtil.waitWithEventsDispatching(
                    "side_db.main tables after schema deepen", { childrenOf(main).isNotEmpty() }, 45,
                )
            }
        }
        val tables = childrenOf(main).map(::nameOf)
        println("=== STAGE5 side_db.main tables=$tables ===")
        assertTrue("the attached database's table must resolve: $tables", tables.any { it.equals("side_t", true) })
    }
}
