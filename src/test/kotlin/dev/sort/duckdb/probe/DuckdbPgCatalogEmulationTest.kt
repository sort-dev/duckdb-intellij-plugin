package dev.sort.duckdb.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * INTROSPECTOR DETERMINATION (deliverable C). Two independent facts decide what introspects a
 * DUCKDB_BRIKK data source, and this test pins both:
 *
 *  1. VERSION GATE (proven from SDK bytecode, recorded in REPORT-stage5-tree.md):
 *     `PgIntrospector.Factory.isSupported(v)` compiles to `v.isOrGreater(9)`, and selection runs
 *     `INTRO_EP.forDbms(dbms).isNative() && isSupported(version)`. DuckDB reports major version 1
 *     and does NOT emulate `server_version`, so the PG native introspector's gate fails and the
 *     platform falls back to the generic JDBC introspector (JdbcIntrospector, isNative=false /
 *     isSupported=always). `major version below 9` below is that load-bearing fact as a live test.
 *
 *  2. pg_catalog EMULATION: even if the gate somehow passed, the PG introspector's queries only work
 *     to the extent DuckDB emulates pg_catalog. DuckDB emulates the introspection BACKBONE
 *     (pg_class/namespace/attribute/constraint/type/proc/depend + core functions) but NOT the
 *     role/authorization/size catalogs. Both halves are pinned here.
 *
 * Each probe uses its OWN in-memory connection: DuckDB leaves a stale "pending query result" on the
 * connection right after an error, so a shared statement would report false MISSINGs on the query
 * following any real miss. Isolation makes the inventory deterministic.
 */
class DuckdbPgCatalogEmulationTest {

    private fun exists(sql: String, vararg setup: String): Boolean =
        try {
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                c.createStatement().use { s -> setup.forEach { s.execute(it) } }
                c.createStatement().use { s -> s.executeQuery(sql).use { true } }
            }
        } catch (e: Exception) {
            false
        }

    // Backbone objects a PG-style introspector reads first. Stable across DuckDB patch releases.
    private val backbone = listOf(
        "pg_catalog.pg_class", "pg_catalog.pg_namespace", "pg_catalog.pg_attribute",
        "pg_catalog.pg_constraint", "pg_catalog.pg_type", "pg_catalog.pg_proc",
        "pg_catalog.pg_depend", "pg_catalog.pg_index",
    )
    private val backboneFns = listOf(
        "version()", "current_schema()", "current_database()",
        "format_type(NULL, NULL)", "pg_table_is_visible(0)",
    )
    // Convenience views DuckDB also ships.
    private val views = listOf("pg_catalog.pg_tables", "pg_catalog.pg_views", "pg_catalog.pg_settings", "pg_catalog.pg_database")
    // Absent in DuckDB 1.5.5 — the introspection-relevant GAPS.
    private val absentTables = listOf("pg_catalog.pg_roles", "pg_catalog.pg_authid", "pg_catalog.pg_user", "pg_catalog.pg_matviews", "pg_catalog.pg_inherits", "pg_catalog.pg_trigger")

    @Test
    fun `pg_catalog introspection backbone is emulated`() {
        val report = StringBuilder("\n=== pg_catalog emulation (DuckDB 1.5.5) ===\n")
        val missing = ArrayList<String>()
        for (t in backbone + views) {
            val ok = exists("SELECT count(*) FROM $t")
            report.append("  ${if (ok) "EXISTS " else "MISSING"} $t\n")
            if (t in backbone && !ok) missing.add(t)
        }
        for (f in backboneFns) {
            val ok = exists("SELECT $f")
            report.append("  ${if (ok) "EXISTS " else "MISSING"} $f\n")
            if (!ok) missing.add(f)
        }
        println(report)
        assertTrue("pg_catalog backbone not fully emulated — missing $missing", missing.isEmpty())
    }

    @Test
    fun `a PG-style introspection join runs over the emulated catalog`() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY, name VARCHAR)")
                s.execute("CREATE TABLE child (cid INTEGER, pid INTEGER REFERENCES parent(id))")
            }
            // relkind classification (this is the shape of a real L1 table-list query)
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT n.nspname, c.relname, c.relkind FROM pg_catalog.pg_class c " +
                        "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE c.relkind = 'r' AND c.relname IN ('parent','child') ORDER BY c.relname",
                ).use { rs ->
                    val seen = ArrayList<String>()
                    while (rs.next()) seen.add("${rs.getString("nspname")}.${rs.getString("relname")}")
                    println("=== pg_class join === $seen")
                    assertTrue("pg_class/pg_namespace join did not resolve user tables: $seen", seen.containsAll(listOf("main.child", "main.parent")))
                }
            }
            // column typing + NOT NULL via pg_attribute + format_type — the column-level L1 query
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS t, a.attnotnull " +
                        "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid = a.attrelid " +
                        "WHERE c.relname = 'parent' AND a.attnum > 0 ORDER BY a.attnum",
                ).use { rs ->
                    val cols = LinkedHashMap<String, Pair<String, Boolean>>()
                    while (rs.next()) cols[rs.getString("attname")] = rs.getString("t") to rs.getBoolean("attnotnull")
                    println("=== pg_attribute join === $cols")
                    assertTrue("pg_attribute did not resolve column 'id'", cols.containsKey("id"))
                    assertEquals("PK column should read attnotnull=true", true, cols["id"]!!.second)
                    assertEquals("plain column should read attnotnull=false", false, cols["name"]!!.second)
                }
            }
        }
    }

    @Test
    fun `role, authorization and size catalogs are NOT emulated`() {
        // GAP: these back the PG introspector's role/ACL/size queries. Their absence is a second,
        // independent reason the native PG introspector is the wrong engine for DuckDB — beyond the
        // version gate. If a future DuckDB adds them, THIS fails and we re-open the introspector
        // decision (see the decision memo in REPORT-stage5-tree.md).
        val present = absentTables.filter { exists("SELECT count(*) FROM $it") }
        println("=== expected-absent pg_catalog objects, now present: $present ===")
        assertTrue("pg_catalog objects previously absent are now emulated: $present — revisit introspector choice", present.isEmpty())
    }

    @Test
    fun `reported major version is below the PG native-introspector gate`() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            val md = c.metaData
            val major = md.databaseMajorVersion
            println("=== version === product=${md.databaseProductName} version=${md.databaseProductVersion} major=$major")
            // PgIntrospector.Factory.isSupported == version.isOrGreater(9). DuckDB reports major 1,
            // so the native PG introspector is never selected — the generic JDBC introspector runs.
            assertTrue("DuckDB now reports major >= 9 (PG native-introspector gate) — re-check introspector routing (major=$major)", major < 9)
            // And there is no PG-compatible server_version to lift it over the gate.
            assertFalse(
                "DuckDB now emulates server_version — this could flip the introspector version gate",
                exists("SELECT current_setting('server_version')"),
            )
        }
    }
}
