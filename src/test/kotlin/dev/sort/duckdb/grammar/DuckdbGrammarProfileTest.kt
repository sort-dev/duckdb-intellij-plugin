package dev.sort.duckdb.grammar

import dev.brikk.house.sql.verify.ConnectionDuckdbVerifier
import dev.brikk.house.sql.verify.JdbcDuckdbQueryConnection
import dev.brikk.house.sql.verify.VerifyResult
import org.junit.Assert.*
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList

class DuckdbGrammarProfileTest {
    private class Fake : GrammarConnection {
        val statements = CopyOnWriteArrayList<String>()
        var active = "[]"
        override fun rows(sql: String): List<Map<String, Any?>> {
            statements += sql
            return when (sql) {
                DuckdbGrammarDiscovery.AVAILABLE -> listOf("second", "first").map { linkedMapOf("name" to it, "description" to "a module is not a grammar") }
                DuckdbGrammarDiscovery.EFFECTIVE -> listOf(mapOf("json" to active))
                else -> error(sql)
            }
        }
        override fun execute(sql: String) {
            statements += sql
            active = "[\"second\",\"first\"]"
        }
    }

    @Test fun `new registered grammars default on and missing configured names remain visible`() {
        val snapshot = DuckdbGrammarDiscovery.read(Fake())
        assertEquals(emptyList<String>(), snapshot.effective)
        val profile = DuckdbGrammarProfile(listOf("gone")).discovered(snapshot.available)
        assertEquals(listOf("gone", "first", "second"), profile.enabled)
        assertEquals(listOf(GrammarEntry("gone", true, false), GrammarEntry("first", true, true), GrammarEntry("second", true, true)), snapshot.entries(profile))
    }

    @Test fun `discovery preserves explicit disables across scans and missing registrations`() {
        val profile = DuckdbGrammarProfile(listOf("user_second", "user_first")).discovered(listOf("zeta", "alpha"))
            .select("alpha", false)
        assertEquals(listOf("user_second", "user_first", "zeta"), profile.enabled)
        assertEquals(listOf("alpha"), profile.disabled)
        assertEquals(profile, profile.discovered(emptyList()).discovered(listOf("alpha", "zeta")))
        assertEquals(listOf("user_second", "user_first", "zeta", "beta", "gamma"), profile.discovered(listOf("gamma", "beta", "alpha")).enabled)
    }

    @Test fun `activation is ordered and never loads a guessed module`() {
        val connection = Fake()
        val profile = DuckdbGrammarProfile(listOf("second", "first"))
        assertEquals(profile.enabled, DuckdbGrammarDiscovery.apply(connection, profile).effective)
        assertEquals(listOf(profile.activationSql), connection.statements.filter { it.startsWith("SET") })
        assertFalse(connection.statements.any { it.startsWith("LOAD") || it.startsWith("INSTALL") })
        assertEquals("SET active_grammar_extensions = ['a''; LOAD evil; --', '😊']",
            DuckdbGrammarProfile(listOf("a'; LOAD evil; --", "😊")).activationSql)
    }

    @Test fun `missing names refuse initialization rather than silently activate base grammar`() {
        val connection = Fake()
        assertThrows(IllegalStateException::class.java) {
            DuckdbGrammarDiscovery.apply(connection, DuckdbGrammarProfile(listOf("not_registered")))
        }
        assertFalse(connection.statements.any { it.startsWith("SET") })
    }

    @Test fun `memory options and routed locations are never classified as local files`() {
        for (url in listOf(null, "jdbc:duckdb:", "jdbc:duckdb:;threads=1", "jdbc:duckdb:?threads=1",
            "jdbc:duckdb::memory:", "jdbc:duckdb::memory:;threads=1", "jdbc:duckdb:md:my_db",
            "jdbc:duckdb:quack://host/db", "jdbc:duckdb:https://host/db", "jdbc:quack://host/db")) {
            assertFalse("Must not open an unrelated helper: $url", DuckdbGrammarVerification.isFileBacked(url))
        }
        for (url in listOf("jdbc:duckdb:/data/demo.db", "jdbc:duckdb:relative.db;threads=1", "jdbc:duckdb:C:\\data\\demo.db")) {
            assertTrue(url, DuckdbGrammarVerification.isFileBacked(url))
        }
    }

    private fun jdbc(connection: Connection) = object : GrammarConnection {
        override fun rows(sql: String): List<Map<String, Any?>> = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).associate { rows.metaData.getColumnLabel(it) to rows.getString(it) })
                }
            }
        }
        override fun execute(sql: String) { connection.createStatement().use { it.execute(sql) } }
    }

    @Test fun `DuckDB 2 discovery and exact empty activation use qualified builtins`() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val snapshot = DuckdbGrammarDiscovery.apply(jdbc(connection), DuckdbGrammarProfile())
            assertTrue(snapshot.supported)
            assertEquals(emptyList<String>(), snapshot.effective)
            assertThrows(IllegalStateException::class.java) {
                DuckdbGrammarDiscovery.apply(jdbc(connection), DuckdbGrammarProfile(listOf("missing_grammar")))
            }
        }
    }

    @Test fun `managed helper initialization parses without executing authored SQL`() {
        val url = "jdbc:duckdb:"
        DriverManager.getConnection(url).use { source ->
            source.createStatement().use { it.execute("CREATE SEQUENCE untouched START 42") }
            val verifier = ConnectionDuckdbVerifier(connect = {
                // Public JDBC duplicate is safe in this in-process test, not a claim about IDE remote duplication.
                val helper = (source as org.duckdb.DuckDBConnection).duplicate()
                DuckdbGrammarDiscovery.apply(jdbc(helper), DuckdbGrammarProfile())
                JdbcDuckdbQueryConnection(helper)
            })
            assertEquals(VerifyResult.Accepted, verifier.verify("SELECT nextval('untouched')"))
            val sql = "SELECT '😊'\r\nFROM WHERE"
            val rejected = verifier.verify(sql) as VerifyResult.Rejected
            assertEquals(sql.indexOf("WHERE"), rejected.error.offset)
            source.createStatement().use { statement ->
                statement.executeQuery("SELECT nextval('untouched')").use { assertTrue(it.next()); assertEquals(42, it.getInt(1)) }
            }
        }
    }
}
