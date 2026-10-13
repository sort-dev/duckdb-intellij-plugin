package dev.sort.duckdb.catalog

import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.dialects.postgres.model.PgRoot
import com.intellij.database.model.DasObject
import com.intellij.database.model.ModelFactory
import com.intellij.database.remote.jdbc.helpers.GenericJdbcHelper
import com.intellij.database.remote.jdbc.impl.RemoteConnectionImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.duckdb.DuckdbDbms
import org.duckdb.DuckDBDriver
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.Properties

/** Exercise the registered provider and its catalog reads through the platform's JDBC wrapper. */
class DuckdbDefinitionProviderTest : BasePlatformTestCase() {
    private lateinit var engine: Connection
    private val provider = DuckdbDefinitionProvider()

    override fun setUp() {
        super.setUp()
        engine = DuckDBDriver().connect("jdbc:duckdb:", Properties())
        execute(
            "CREATE SCHEMA web",
            "CREATE TABLE web.t(id INT PRIMARY KEY, name VARCHAR DEFAULT 'x')",
            "CREATE VIEW web.v AS SELECT id FROM web.t",
            "CREATE MACRO web.add(a, b := 1) AS a + b, (a, b, c) AS a + b + c",
            "CREATE MACRO web.rows(n) AS TABLE SELECT * FROM range(n)",
            "CREATE SEQUENCE web.seq START 5",
            "CREATE INDEX ix ON web.t(name)",
            "ATTACH ':memory:' AS lake",
            "CREATE TABLE lake.main.t(other INT)",
        )
    }

    override fun tearDown() {
        try { engine.close() } finally { super.tearDown() }
    }

    private fun execute(vararg sql: String) = engine.createStatement().use { statement -> sql.forEach { statement.execute(it) } }

    private fun databases() = (ModelFactory.BLACK_HOLE.createModel(DuckdbDbms.DUCKDB_BRIKK).root as PgRoot).databases

    private fun load(vararg objects: DasObject): String {
        val remote = RemoteConnectionImpl(engine, GenericJdbcHelper())
        val source = LocalDataSource()
        val requestor = ConnectionRequestor.Anonymous()
        val connection = Proxy.newProxyInstance(DatabaseConnection::class.java.classLoader, arrayOf(DatabaseConnection::class.java)) { proxy, method, args ->
            when (method.name) {
                "getRemoteConnection" -> remote
                "getConnectionPoint" -> source
                "getDbms" -> DuckdbDbms.DUCKDB_BRIKK
                "getRequestor" -> requestor
                "getAutoCommit" -> engine.autoCommit
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "definition test connection"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as DatabaseConnection
        return StringBuilder().also {
            provider.load(objects.toList(), connection, it)
        }.toString()
    }

    fun testRegisteredProviderReadsAllFiveKindsAndAttachedCatalogs() {
        val registered = com.intellij.database.dialects.DefinitionProvider.EP.forDbms(DuckdbDbms.DUCKDB_BRIKK)
        assertTrue(registered.toString(), registered is DuckdbDefinitionProvider)
        val databases = databases()
        val web = databases.createOrGet("memory").schemas.createOrGet("web")
        val table = web.tables.createOrGet("t")
        assertTrue(provider.isSupported(table))
        assertFalse(provider.isSupported(web))
        assertTrue(load(table).contains("CREATE TABLE web.t("))
        assertTrue(load(web.views.createOrGet("v")).contains("CREATE VIEW web.v AS"))
        assertTrue(load(web.sequences.createOrGet("seq")).contains("START 5"))
        assertTrue(load(table.indices.createOrGet("ix")).contains("CREATE INDEX ix"))
        assertTrue(load(web.routines.createOrGet("add")).contains("CREATE MACRO \"web\".\"add\""))
        val attached = databases.createOrGet("lake").schemas.createOrGet("main").tables.createOrGet("t")
        assertTrue(load(attached), load(attached).contains("CREATE TABLE t(other INTEGER)"))
    }

    fun testMacrosKeepOverloadsQuoteParametersAndDiscloseMissingDefaults() {
        val web = databases().createOrGet("memory").schemas.createOrGet("web")
        val add = load(web.routines.createOrGet("add"))
        assertTrue(add, add.contains("DuckDB does not report macro parameter defaults; any are omitted here."))
        assertTrue(add, add.contains("CREATE MACRO \"web\".\"add\"(\"a\", \"b\") AS (a + b), (\"a\", \"b\", \"c\") AS ((a + b) + c);"))
        assertTrue(load(web.routines.createOrGet("rows")).contains("AS TABLE SELECT * FROM \"range\"(n)"))
        execute("CREATE MACRO web.\"select\"(\"a b\", \"c, d\", \"e\"\"f\") AS 1")
        val quoted = load(web.routines.createOrGet("select"))
        assertTrue(quoted, quoted.contains("\"select\"(\"a b\", \"c, d\", \"e\"\"f\") AS 1;"))
        execute("DROP MACRO web.add")
        assertFalse(load(web.routines.createOrGet("add")).contains("CREATE MACRO"))
    }

    fun testCatalogAndHelperShadowMacrosNeverRun() {
        execute("CREATE SEQUENCE calls")
        for (name in listOf("duckdb_tables", "duckdb_views", "duckdb_functions", "duckdb_sequences", "duckdb_indexes")) {
            execute("CREATE MACRO $name() AS TABLE SELECT nextval('calls')::VARCHAR AS sql, 'memory' AS database_name, 'web' AS schema_name, " +
                "'t' AS table_name, 'v' AS view_name, 'seq' AS sequence_name, 'ix' AS index_name, 'add' AS function_name, " +
                "'macro' AS function_type, ['a'] AS parameters, '1' AS macro_definition, 0 AS function_oid, false AS internal")
        }
        for (name in listOf("array_to_string", "list_aggr", "list_transform", "concat", "replace", "unnest", "list_extract", "array_extract")) {
            execute("CREATE MACRO $name(a := 0, b := 0, c := 0) AS nextval('calls')")
        }
        val web = databases().createOrGet("memory").schemas.createOrGet("web")
        val table = web.tables.createOrGet("t")
        val objects = listOf(table, web.views.createOrGet("v"), web.sequences.createOrGet("seq"),
            table.indices.createOrGet("ix"), web.routines.createOrGet("add"))
        for (obj in objects) {
            val query = requireNotNull(provider.query(obj))
            assertTrue(query.sql, query.sql.contains("system.main.duckdb_"))
            assertTrue(load(obj), load(obj).contains("CREATE "))
        }
        engine.createStatement().use { statement ->
            val error = runCatching { statement.executeQuery("SELECT currval('calls')").use { it.next() } }.exceptionOrNull()
            assertTrue("No macro may advance the sequence: $error", error?.message.orEmpty().contains("not yet defined"))
        }
    }

    fun testDuckdbHasNoTextExplainOptIn() {
        assertTrue(dev.brikk.house.sql.shape.prepareTextExplain("SELECT 1", "duckdb") { true } is dev.brikk.house.sql.shape.ExplainPreparation.Refused)
    }
}
