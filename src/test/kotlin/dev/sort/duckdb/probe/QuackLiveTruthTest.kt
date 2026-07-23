package dev.sort.duckdb.probe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * STAGE-5/6 TRUTH over the WIRE: the same metadata + harvest + console battery as the native-driver
 * suites, but run through the GizmoSQL quack driver against a real server. This is the only place
 * quack's [DatabaseMetaData] is exercised for real (the static audit in [QuackMetadataFactsTest]
 * only proves the methods exist); the value is the native-vs-quack behavioral DIFFERENCES, recorded
 * inline and in REPORT-stage5-tree.md.
 *
 * GATED + OFFLINE-DETERMINISTIC: every test JUnit-Assumes out unless `-Dquack.live.url=...` is set
 * (forwarded to the test JVM by build.gradle.kts). The committed suite therefore never needs a
 * server. Run live with:
 *   ./gradlew test -Dquack.live.url='jdbc:quack://localhost:9494?token=truth-token'
 * Server recipe:
 *   docker run -d --name quack -p 9494:9494 -e QUACK_TOKEN=<token> brikk-ducklake-quack-server:latest
 *
 * The server is shared with other lanes: objects use a per-instance unique suffix and are dropped in
 * tearDown.
 */
class QuackLiveTruthTest {

    private val url: String? = System.getProperty("quack.live.url")
    private val sfx = "_" + java.lang.Long.toHexString(System.nanoTime())
    private val parent = "ttl_parent$sfx"
    private val child = "ttl_child$sfx"
    private val view = "ttl_v$sfx"
    private val idx = "ttl_idx$sfx"
    private var conn: Connection? = null

    @Before
    fun setUp() {
        assumeTrue("quack.live.url not set — live quack suite skipped (offline-deterministic)", !url.isNullOrBlank())
        val c = DriverManager.getConnection(url)
        conn = c
        c.createStatement().use { s ->
            s.execute("DROP VIEW IF EXISTS $view")
            s.execute("DROP TABLE IF EXISTS $child")
            s.execute("DROP TABLE IF EXISTS $parent")
            s.execute("CREATE TABLE $parent (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL DEFAULT 'anon')")
            s.execute(
                "CREATE TABLE $child (" +
                    "child_id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES $parent(id), " +
                    "qty INTEGER NOT NULL DEFAULT 1, tags VARCHAR[], " +
                    "profile STRUCT(a INTEGER, b VARCHAR), amount DECIMAL(10,2))"
            )
            s.execute("CREATE VIEW $view AS SELECT child_id, qty FROM $child")
            s.execute("CREATE INDEX $idx ON $child(parent_id)")
        }
    }

    @After
    fun tearDown() {
        conn?.let { c ->
            runCatching {
                c.createStatement().use { s ->
                    s.execute("DROP VIEW IF EXISTS $view")
                    s.execute("DROP TABLE IF EXISTS $child")
                    s.execute("DROP TABLE IF EXISTS $parent")
                }
            }
            runCatching { c.close() }
        }
    }

    private fun rows(rs: ResultSet): List<Map<String, String?>> {
        val md = rs.metaData
        val out = ArrayList<Map<String, String?>>()
        rs.use { while (rs.next()) out.add((1..md.columnCount).associate { md.getColumnLabel(it) to rs.getString(it) }) }
        return out
    }

    private fun scalar(sql: String): String =
        conn!!.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getString(1) } }

    @Test
    fun `quack identity and version skew are as expected`() {
        val md = conn!!.metaData
        println("=== quack identity === product=${md.databaseProductName} version=${md.databaseProductVersion} " +
            "major=${md.databaseMajorVersion} driver=${md.driverName} ${md.driverVersion} getCatalog=${conn!!.catalog}")
        // DIFF vs native: product carries "(via Quack)"; server engine is 1.5.3 (deliberate skew
        // from our bundled 1.5.5); getCatalog() is null over the wire (native returns the db name).
        assertTrue("product name should mark the quack path: ${md.databaseProductName}", md.databaseProductName.contains("Quack", ignoreCase = true))
        assertTrue("server should still be a DuckDB major 1", md.databaseMajorVersion == 1)
    }

    @Test
    fun `getCatalogs lists server catalogs and an over-the-wire ATTACH`() {
        val md = conn!!.metaData
        val before = rows(md.catalogs).mapNotNull { it["TABLE_CAT"] }
        println("=== quack getCatalogs (before) === $before")
        assertTrue("expected server catalogs memory/system/temp, got $before", before.containsAll(listOf("memory", "system", "temp")))
        // ATTACH over the wire must surface as a catalog — the multi-database tree story holds on quack.
        val alias = "ttl_att$sfx"
        conn!!.createStatement().use { it.execute("ATTACH ':memory:' AS $alias") }
        try {
            val after = rows(md.catalogs).mapNotNull { it["TABLE_CAT"] }
            println("=== quack getCatalogs (after ATTACH $alias) === $after")
            assertTrue("ATTACHed alias '$alias' not visible in getCatalogs over the wire: $after", after.contains(alias))
        } finally {
            runCatching { conn!!.createStatement().use { it.execute("DETACH $alias") } }
        }
    }

    @Test
    fun `getSchemas and getTables with catalog filter over the wire`() {
        val md = conn!!.metaData
        val schemas = rows(md.schemas)
        assertTrue("getSchemas empty over the wire", schemas.isNotEmpty())
        assertTrue("no 'main' schema over the wire", schemas.any { it["TABLE_SCHEM"] == "main" })

        val mine = rows(md.getTables(null, null, "ttl_%$sfx", arrayOf("TABLE", "VIEW"))).map { it["TABLE_NAME"] to it["TABLE_TYPE"] }
        println("=== quack getTables(ttl_%) === $mine")
        assertTrue("parent table not visible over the wire", mine.any { it.first == parent && it.second == "TABLE" })
        assertTrue("view not typed VIEW over the wire", mine.any { it.first == view && it.second == "VIEW" })
        // Catalog filter narrows: 'system' is the built-in catalog, must not leak my 'memory' tables.
        val systemTables = rows(md.getTables("system", null, "%", null))
        assertTrue("catalog filter to 'system' returned nothing", systemTables.isNotEmpty())
        assertTrue("catalog filter leaked non-system catalogs: ${systemTables.map { it["TABLE_CAT"] }.toSet()}",
            systemTables.all { it["TABLE_CAT"] == "system" })
    }

    @Test
    fun `getColumns preserves STRUCT LIST DECIMAL NOT NULL and DEFAULT over the wire`() {
        val cols = rows(conn!!.metaData.getColumns(null, null, child, null)).associateBy { it["COLUMN_NAME"] }
        cols.values.forEach { println("  quack col ${it["COLUMN_NAME"]} | ${it["TYPE_NAME"]} | DATA_TYPE=${it["DATA_TYPE"]} | NULLABLE=${it["NULLABLE"]} | DEF=${it["COLUMN_DEF"]}") }
        // Identical to native: quack's getColumns keeps the full DuckDB type spelling.
        assertTrue("STRUCT type not preserved over quack: ${cols["profile"]!!["TYPE_NAME"]}", cols["profile"]!!["TYPE_NAME"]!!.startsWith("STRUCT"))
        assertTrue("LIST type not preserved over quack: ${cols["tags"]!!["TYPE_NAME"]}", cols["tags"]!!["TYPE_NAME"]!!.contains("[]"))
        assertTrue("DECIMAL not preserved over quack", cols["amount"]!!["TYPE_NAME"]!!.startsWith("DECIMAL"))
        assertEquals("NOT NULL lost over quack", DatabaseMetaData.columnNoNulls, cols["qty"]!!["NULLABLE"]!!.toInt())
        assertEquals("DEFAULT lost over quack", "1", cols["qty"]!!["COLUMN_DEF"])
    }

    @Test
    fun `getPrimaryKeys getImportedKeys getTypeInfo getTableTypes over the wire`() {
        val md = conn!!.metaData
        val pk = rows(md.getPrimaryKeys(null, null, parent))
        assertTrue("PK column 'id' not reported over quack", pk.any { it["COLUMN_NAME"] == "id" })

        val fks = rows(md.getImportedKeys(null, null, child))
        println("=== quack getImportedKeys === $fks")
        assertTrue("FK not reported over quack", fks.isNotEmpty())
        val fk = fks.first()
        assertEquals("FK column wrong over quack", "parent_id", fk["FKCOLUMN_NAME"])
        assertEquals("FK target table wrong over quack", parent, fk["PKTABLE_NAME"])
        assertEquals("FK target column wrong over quack", "id", fk["PKCOLUMN_NAME"])
        // DIFF vs native: quack fills FK_NAME + PK_NAME + UPDATE_RULE/DELETE_RULE/DEFERRABILITY on
        // getImportedKeys (native leaves PK_NAME null there). Recorded, not asserted strictly.
        assertTrue("quack unexpectedly dropped FK_NAME — revisit report", fk["FK_NAME"] != null)

        assertTrue("getTableTypes missing TABLE/VIEW over quack", rows(md.tableTypes).map { it["TABLE_TYPE"] }.containsAll(listOf("TABLE", "VIEW")))
        assertFalse("getTypeInfo empty over quack", rows(md.typeInfo).isEmpty())
    }

    @Test
    fun `Stage-4b harvest queries return facts over the wire`() {
        val version = scalar("SELECT version()")
        val fnCount = scalar("SELECT count(*) FROM duckdb_functions()").toInt()
        val kwCount = scalar("SELECT count(*) FROM duckdb_keywords()").toInt()
        val loaded = rows(conn!!.createStatement().executeQuery("SELECT extension_name FROM duckdb_extensions() WHERE loaded ORDER BY 1")).mapNotNull { it["extension_name"] }
        println("=== quack harvest === version=$version functions=$fnCount keywords=$kwCount loaded=$loaded")
        // Version skew is the point: the server is its own engine version, independent of what we bundle.
        assertTrue("version() not a DuckDB v1 string over the wire: $version", version.startsWith("v1"))
        assertTrue("duckdb_functions() catalog implausibly small over the wire: $fnCount", fnCount > 500)
        assertTrue("duckdb_keywords() implausibly small over the wire: $kwCount", kwCount > 100)
        assertTrue("expected the 'quack' server extension loaded: $loaded", loaded.contains("quack"))
    }

    @Test
    fun `SUMMARIZE DESCRIBE EXPLAIN render over the wire`() {
        for ((label, sql) in linkedMapOf("SUMMARIZE" to "SUMMARIZE $parent", "DESCRIBE" to "DESCRIBE $parent", "EXPLAIN" to "EXPLAIN SELECT * FROM $parent")) {
            conn!!.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    val cols = rs.metaData.columnCount
                    var n = 0; while (rs.next()) n++
                    println("=== quack $label === cols=$cols rows=$n")
                    assertTrue("$label produced no columns over quack", cols >= 1)
                    assertTrue("$label produced no rows over quack", n >= 1)
                }
            }
        }
    }

    @Test
    fun `autocommit and BEGIN ROLLBACK round-trip over the wire`() {
        val c = conn!!
        assertTrue("expected autoCommit=true over quack", c.autoCommit)
        c.createStatement().use { s ->
            s.execute("INSERT INTO $parent VALUES (1,'a'),(2,'b')")
            assertEquals("2", scalar("SELECT count(*) FROM $parent"))
            s.execute("BEGIN TRANSACTION")
            s.execute("INSERT INTO $parent VALUES (99,'z')")
            assertEquals("insert not visible mid-transaction over quack", "3", scalar("SELECT count(*) FROM $parent"))
            s.execute("ROLLBACK")
            assertEquals("ROLLBACK via execute() did not undo over quack", "2", scalar("SELECT count(*) FROM $parent"))
        }
    }
}
