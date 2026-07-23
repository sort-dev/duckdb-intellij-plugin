package dev.sort.duckdb.catalog

import dev.sort.duckdb.sql.DuckdbFunctionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * The harvest over a REAL quack wire (GizmoSQL server, remote transport — the other of the two
 * driver families the plugin templates ship): the same [CatalogRowSource]-fed code the connect
 * interceptor runs, against a server whose engine is deliberately NOT our bundled 1.5.5 — the
 * replace-not-merge rule in the flesh (the entry must carry the SERVER's version and inventory).
 *
 * Opt-in: needs a running server, so it gates on `-Dquack.live.url=jdbc:quack://host:port?token=...`
 * (build.gradle.kts passes the property through); absent -> Assume skips, the suite stays
 * offline-green. The committed code carries no URL and no token.
 */
class DuckdbLiveHarvestOverQuackTest {

    @Test
    fun `harvest over a live quack connection carries the server engine, not the bundled one`() {
        val url = System.getProperty("quack.live.url")
        assumeTrue("quack.live.url not set — live wire test skipped", !url.isNullOrBlank())
        // quack-jdbc 0.4.0 is a test-scope dependency; make sure DriverManager sees it.
        Class.forName("com.gizmodata.quack.jdbc.sql.QuackDriver")
        DriverManager.getConnection(url).use { connection ->
            val entry = DuckdbCatalogHarvester.harvest(JdbcRowSource(connection))
            assertNotNull("harvest over the quack wire must succeed", entry)
            entry!!
            // Version-exact from the wire: the lab server runs 1.5.3 — the bundled 1.5.5 snapshot
            // must be nowhere in this entry.
            assertTrue("server version, got ${entry.engineVersion}", entry.engineVersion.contains("1.5.3"))
            assertFalse("bundled version must not leak in", entry.engineVersion.contains("1.5.5"))
            val kinds = entry.functions.mapTo(HashSet()) { it.kind }
            assertTrue(
                "expected scalar/aggregate/table kinds at minimum, got $kinds",
                kinds.containsAll(
                    listOf(
                        DuckdbFunctionCatalog.Kind.SCALAR,
                        DuckdbFunctionCatalog.Kind.AGGREGATE,
                        DuckdbFunctionCatalog.Kind.TABLE,
                    ),
                ),
            )
            assertTrue("functions: ${entry.functions.size}", entry.functions.isNotEmpty())
            assertTrue("keywords must land over the wire", entry.keywords.isNotEmpty())
            // Server-side loaded set (the server links httpfs/icu; our in-process 1.5.5 has no httpfs).
            assertTrue(
                "loaded: ${entry.loadedExtensions}",
                entry.loadedExtensions.containsAll(listOf("httpfs", "icu")),
            )
            assertEquals(entry.loadedExtensions.sorted(), entry.loadedExtensions)
        }
    }
}
