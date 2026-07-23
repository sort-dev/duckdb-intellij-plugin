package dev.sort.duckdb.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * STAGE-5 TRUTH: what the NATIVE DuckDB JDBC driver (org.duckdb:duckdb_jdbc) reports through
 * [DatabaseMetaData]. This is the surface the platform's generic JDBC introspector reads for a
 * DUCKDB_BRIKK data source (see [DuckdbPgCatalogEmulationTest] for WHY the generic introspector,
 * not the PG one, runs). These are facts, not aspirations: where the driver returns empty or lies,
 * the current behavior is pinned with a `// GAP:` comment and recorded in REPORT-stage5-tree.md so
 * it becomes an upstream item / tree-feature constraint rather than a silent surprise.
 *
 * Multi-catalog is exercised for real: a primary file DB, a second ATTACHed file DB, and an
 * ATTACHed in-memory alias — because DuckDB's ATTACH is exactly the tree's "several databases in
 * one connection" case.
 */
class DuckdbJdbcMetadataTruthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rows(rs: ResultSet): List<Map<String, String?>> {
        val md = rs.metaData
        val n = md.columnCount
        val out = ArrayList<Map<String, String?>>()
        rs.use {
            while (rs.next()) {
                val row = LinkedHashMap<String, String?>()
                for (i in 1..n) row[md.getColumnLabel(i)] = rs.getString(i)
                out.add(row)
            }
        }
        return out
    }

    /** Builds the primary file DB (PK / FK / NOT NULL / DEFAULT / STRUCT / LIST / view / seq / index),
     *  a second file DB, then ATTACHes the second file DB and an in-memory alias. */
    private fun withAttachedModel(block: (Connection, mainCat: String) -> Unit) {
        // Paths under the temp root that do NOT pre-exist — DuckDB rejects opening a pre-created
        // empty file ("not a valid DuckDB database file"), so we let the driver create them.
        val mainDb = java.io.File(tmp.root, "shop.duckdb").absolutePath
        val secondDb = java.io.File(tmp.root, "warehouse.duckdb").absolutePath
        // Second DB is created stand-alone first, then attached — mirrors a real ATTACH target.
        DriverManager.getConnection("jdbc:duckdb:$secondDb").use { c2 ->
            c2.createStatement().use { s ->
                s.execute("CREATE TABLE remote_t (rid INTEGER PRIMARY KEY, label VARCHAR)")
                s.execute("INSERT INTO remote_t VALUES (1, 'r')")
            }
        }
        DriverManager.getConnection("jdbc:duckdb:$mainDb").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL DEFAULT 'anon')")
                s.execute(
                    "CREATE TABLE child (" +
                        "child_id INTEGER PRIMARY KEY, " +
                        "parent_id INTEGER REFERENCES parent(id), " +
                        "qty INTEGER NOT NULL DEFAULT 1, " +
                        "tags VARCHAR[], " +
                        "profile STRUCT(a INTEGER, b VARCHAR), " +
                        "amount DECIMAL(10,2))"
                )
                s.execute("CREATE VIEW child_v AS SELECT child_id, qty FROM child")
                s.execute("CREATE SEQUENCE seq_demo START 100")
                s.execute("CREATE INDEX idx_child_parent ON child(parent_id)")
                s.execute("ATTACH '$secondDb' AS warehouse")
                s.execute("ATTACH ':memory:' AS scratch")
                s.execute("CREATE TABLE scratch.mt (x INTEGER)")
            }
            // The primary catalog name is DuckDB's own (file basename, with quirks) — read it from
            // the driver rather than hardcoding, so the assertions never depend on the naming rule.
            block(c, c.catalog)
        }
    }

    @Test
    fun `getCatalogs lists ATTACHed databases`() = withAttachedModel { c, mainCat ->
        val cats = rows(c.metaData.catalogs).map { it["TABLE_CAT"] }
        println("=== getCatalogs === $cats  (primary=$mainCat)")
        // HEADLINE for the tree: ATTACHed databases surface as catalogs. This is the whole basis
        // for showing multiple DuckDB databases under one data source.
        assertTrue("primary catalog '$mainCat' missing from getCatalogs()=$cats", cats.contains(mainCat))
        assertTrue("ATTACHed file DB 'warehouse' not listed as a catalog: $cats", cats.contains("warehouse"))
        assertTrue("ATTACHed :memory: alias 'scratch' not listed as a catalog: $cats", cats.contains("scratch"))
    }

    @Test
    fun `getSchemas returns catalog+schema pairs`() = withAttachedModel { c, _ ->
        val schemas = rows(c.metaData.schemas)
        println("=== getSchemas === $schemas")
        assertTrue("getSchemas() returned no rows", schemas.isNotEmpty())
        // Shape: JDBC getSchemas() carries TABLE_SCHEM + TABLE_CATALOG. DuckDB fills both.
        val first = schemas.first()
        assertTrue("getSchemas missing TABLE_SCHEM column", first.containsKey("TABLE_SCHEM"))
        assertTrue("getSchemas missing TABLE_CATALOG column", first.containsKey("TABLE_CATALOG"))
        // Every DuckDB catalog carries a 'main' schema — the default namespace the tree lands on.
        assertTrue("no 'main' schema present: $schemas", schemas.any { it["TABLE_SCHEM"] == "main" })
    }

    @Test
    fun `getTables sees all catalogs and the catalog filter works`() = withAttachedModel { c, _ ->
        val md = c.metaData
        val all = rows(md.getTables(null, null, "%", arrayOf("TABLE", "VIEW")))
        val names = all.map { "${it["TABLE_CAT"]}.${it["TABLE_NAME"]}(${it["TABLE_TYPE"]})" }
        println("=== getTables(all catalogs) === $names")
        val catalogsSeen = all.map { it["TABLE_CAT"] }.toSet()
        // Cross-catalog visibility: base tables from the primary AND the ATTACHed DBs appear.
        assertTrue("expected tables from >1 catalog, saw $catalogsSeen", catalogsSeen.size > 1)
        assertTrue("child table missing", all.any { it["TABLE_NAME"] == "child" && it["TABLE_TYPE"] == "TABLE" })
        assertTrue("view child_v missing / not typed VIEW", all.any { it["TABLE_NAME"] == "child_v" && it["TABLE_TYPE"] == "VIEW" })
        assertTrue("ATTACHed remote_t not visible", all.any { it["TABLE_NAME"] == "remote_t" })

        // The catalog filter genuinely narrows — pass the explicit ATTACH alias.
        val onlyWarehouse = rows(md.getTables("warehouse", null, "%", null))
        println("=== getTables(catalog=warehouse) === ${onlyWarehouse.map { it["TABLE_CAT"] + "." + it["TABLE_NAME"] }}")
        assertTrue("catalog filter returned nothing for 'warehouse'", onlyWarehouse.isNotEmpty())
        assertTrue(
            "catalog filter leaked other catalogs: ${onlyWarehouse.map { it["TABLE_CAT"] }}",
            onlyWarehouse.all { it["TABLE_CAT"] == "warehouse" },
        )
    }

    @Test
    fun `getColumns reports STRUCT and LIST type names, NOT NULL and DEFAULT`() = withAttachedModel { c, mainCat ->
        val cols = rows(c.metaData.getColumns(mainCat, null, "child", null)).associateBy { it["COLUMN_NAME"] }
        println("=== getColumns(child) ===")
        cols.values.forEach { println("  ${it["COLUMN_NAME"]} | TYPE_NAME=${it["TYPE_NAME"]} | DATA_TYPE=${it["DATA_TYPE"]} | NULLABLE=${it["NULLABLE"]} | DEF=${it["COLUMN_DEF"]}") }

        // STRUCT and LIST keep their full DuckDB type spelling in TYPE_NAME — usable for the tree
        // column tooltip. DATA_TYPE is 2002 (Types.STRUCT) for struct, 1111 (Types.OTHER) for list.
        val profile = cols["profile"]!!
        assertTrue("STRUCT column TYPE_NAME not preserved: ${profile["TYPE_NAME"]}", profile["TYPE_NAME"]!!.startsWith("STRUCT"))
        val tags = cols["tags"]!!
        assertTrue("LIST column TYPE_NAME not preserved: ${tags["TYPE_NAME"]}", tags["TYPE_NAME"]!!.contains("[]"))
        assertTrue("DECIMAL precision/scale not preserved: ${cols["amount"]!!["TYPE_NAME"]}", cols["amount"]!!["TYPE_NAME"]!!.startsWith("DECIMAL"))

        // NOT NULL + DEFAULT are both surfaced (columnNoNulls == 0; COLUMN_DEF carries the literal).
        val qty = cols["qty"]!!
        assertEquals("qty should be NOT NULL (columnNoNulls)", DatabaseMetaData.columnNoNulls, qty["NULLABLE"]!!.toInt())
        assertEquals("qty DEFAULT not reported", "1", qty["COLUMN_DEF"])
        assertEquals("nullable parent_id misreported", DatabaseMetaData.columnNullable, cols["parent_id"]!!["NULLABLE"]!!.toInt())
    }

    @Test
    fun `getPrimaryKeys reports the PK columns`() = withAttachedModel { c, mainCat ->
        val md = c.metaData
        val parentPk = rows(md.getPrimaryKeys(mainCat, null, "parent"))
        val childPk = rows(md.getPrimaryKeys(mainCat, null, "child"))
        println("=== getPrimaryKeys === parent=$parentPk child=$childPk")
        assertTrue("parent PK column 'id' not reported", parentPk.any { it["COLUMN_NAME"] == "id" })
        assertTrue("child PK column 'child_id' not reported", childPk.any { it["COLUMN_NAME"] == "child_id" })
        // GAP: PK_NAME comes back null (no constraint name surfaced). The tree can show the PK from
        // the columns, but has no constraint identity to label it. Upstream item.
        assertTrue("PK_NAME unexpectedly populated — revisit REPORT-stage5-tree.md", parentPk.all { it["PK_NAME"] == null })
    }

    @Test
    fun `getImportedKeys reports the foreign key`() = withAttachedModel { c, mainCat ->
        val fks = rows(c.metaData.getImportedKeys(mainCat, null, "child"))
        println("=== getImportedKeys(child) === $fks")
        assertTrue("child->parent FK not reported at all", fks.isNotEmpty())
        val fk = fks.first()
        assertEquals("FK child column wrong", "parent_id", fk["FKCOLUMN_NAME"])
        assertEquals("FK target table wrong", "parent", fk["PKTABLE_NAME"])
        assertEquals("FK target column wrong", "id", fk["PKCOLUMN_NAME"])
    }

    @Test
    fun `getIndexInfo lists the index`() = withAttachedModel { c, mainCat ->
        val idx = rows(c.metaData.getIndexInfo(mainCat, null, "child", false, false))
        println("=== getIndexInfo(child) === $idx")
        assertTrue("user index idx_child_parent not listed", idx.any { it["INDEX_NAME"] == "idx_child_parent" })
        // GAP: COLUMN_NAME is null on the returned index rows — DuckDB's JDBC getIndexInfo names the
        // index but not the indexed column(s). The tree can list the index, not its column set.
        val ours = idx.first { it["INDEX_NAME"] == "idx_child_parent" }
        assertTrue("index COLUMN_NAME unexpectedly populated — revisit REPORT-stage5-tree.md", ours["COLUMN_NAME"] == null)
    }

    @Test
    fun `getTableTypes and getTypeInfo are populated`() = withAttachedModel { c, _ ->
        val types = rows(c.metaData.tableTypes).map { it["TABLE_TYPE"] }
        println("=== getTableTypes === $types")
        assertTrue("TABLE type missing", types.contains("TABLE"))
        assertTrue("VIEW type missing", types.contains("VIEW"))
        val typeInfo = rows(c.metaData.typeInfo)
        println("=== getTypeInfo === ${typeInfo.size} rows")
        assertFalse("getTypeInfo() returned no rows — grid type mapping would fall back", typeInfo.isEmpty())
    }
}
