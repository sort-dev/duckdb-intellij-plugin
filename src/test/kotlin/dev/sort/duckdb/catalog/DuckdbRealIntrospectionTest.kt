package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.url.template.UrlTemplate
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.util.LoaderContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import dev.sort.duckdb.DuckdbDbms
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/** No held executor and no handwritten model updates: the IDE's JDBC process does every sync. */
class DuckdbRealIntrospectionTest : DuckdbCatalogTestCase() {
    private lateinit var driver: DatabaseDriverImpl
    private lateinit var target: LocalDataSource

    override fun setUp() {
        super.setUp()
        project.basePath?.let { java.nio.file.Files.createDirectories(java.nio.file.Path.of(it)) }
        val main = directory.resolve("primary.duckdb")
        for ((file, schema) in listOf(main to "web", directory.resolve("lake-one.duckdb") to "sales", directory.resolve("lake-two.duckdb") to "sales")) {
            DriverManager.getConnection("jdbc:duckdb:${file.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA $schema")
                    statement.execute("CREATE TABLE $schema.events(id BIGINT NOT NULL, label VARCHAR)")
                    statement.execute("CREATE VIEW $schema.event_view AS SELECT id, label FROM $schema.events")
                    statement.execute("CREATE MACRO $schema.plus_one(x) AS x + 1")
                }
            }
        }
        val jar = System.getProperty("java.class.path").split(File.pathSeparator).first { "duckdb_jdbc" in it }
        driver = DatabaseDriverManager.getInstance().createDriver("introspection-${UUID.randomUUID()}", "Introspection DuckDB",
            UrlTemplate("default", "jdbc:duckdb:{file}")) as DatabaseDriverImpl
        driver.setDriverClass("org.duckdb.DuckDBDriver")
        driver.setSqlDialect("DuckDBSQL")
        driver.setForcedDbms(DuckdbDbms.DUCKDB_BRIKK)
        driver.setAdditionalClasspathElements(SimpleClasspathElementFactory.createElements("jar://$jar!/"))
        driver.setVmOptions("-Djava.io.tmpdir=${directory.toAbsolutePath()}")
        target = source().apply {
            databaseDriver = driver
            url = "jdbc:duckdb:${main.toAbsolutePath()}"
            isAutoSynchronize = false
            isKeepAlive = false
            initScript = "ATTACH IF NOT EXISTS '${directory.resolve("lake-one.duckdb").toAbsolutePath()}' AS lake_one; " +
                "ATTACH IF NOT EXISTS '${directory.resolve("lake-two.duckdb").toAbsolutePath()}' AS lake_two;"
        }
    }

    override fun tearDown() {
        try { super.tearDown() } finally {
            if (::driver.isInitialized) DatabaseDriverManager.getInstance().removeDriver(driver)
        }
    }

    private fun introspect(context: LoaderContext) {
        val outcome = AtomicReference<Result<Boolean>>()
        errors.newScope(testRootDisposable).launch {
            outcome.set(runCatching { DataSourceSyncManager.getInstance().tryPerformSync(context, stopRunning = false, merge = false) != null })
        }
        PlatformTestUtil.waitWithEventsDispatching("real JDBC introspection completes", { outcome.get() != null }, 60)
        assertTrue(context.toString(), outcome.get().getOrThrow())
        assertFalse("Introspection reported database errors: $context", context.errorHandler?.hasErrors() == true)
        errors.assertNone()
    }

    fun testRealGenericJdbcLoadsAttachedCatalogsAndSchemas() {
        introspect(LoaderContext.selectListNamespacesTask(project, target))
        val root = (target.model as com.intellij.database.model.basic.BasicModel).root as PgRoot
        val names = root.databases.map { it.name }
        assertTrue("Attached catalogs missing from real model: $names", names.containsAll(listOf("primary", "lake_one", "lake_two")))
        for (name in listOf("primary", "lake_one", "lake_two")) {
            introspect(LoaderContext.selectTask(project, target, requireNotNull(com.intellij.database.introspection.prepareOneElementRefreshTask(target.uniqueId, root.databases.createOrGet(name)))))
        }
        assertTrue(root.databases.createOrGet("primary").schemas.any { it.name == "web" })
        assertTrue(root.databases.createOrGet("lake_one").schemas.any { it.name == "sales" })
        assertTrue(root.databases.createOrGet("lake_two").schemas.any { it.name == "sales" })
    }
}
