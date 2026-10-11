package dev.sort.duckdb.validate

import com.sun.net.httpserver.HttpServer
import com.intellij.openapi.progress.ProcessCanceledException
import org.duckdb.DuckDBDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class DuckdbEngineValidatorSafetyTest {
    @Test
    fun `cancellation during parser query propagates`() {
        DuckdbEngineValidator.connect(DuckDBDriver())!!.use { connection ->
            for (cancellation in listOf(ProcessCanceledException(), CancellationException())) {
                val interrupted = object : Connection by connection {
                    override fun createStatement(): Statement = throw cancellation
                }
                try {
                    DuckdbEngineValidator.verdict("SELECT 1", interrupted)
                    throw AssertionError("cancellation was swallowed")
                } catch (actual: RuntimeException) {
                    assertSame(cancellation, actual)
                }
            }
        }
    }

    @Test
    fun `explain execution forms never advance a sequence`() {
        DuckdbEngineValidator.connect(DuckDBDriver())!!.use { connection ->
            connection.createStatement().use { it.execute("CREATE SEQUENCE s START 1") }
            for (sql in listOf(
                "EXPLAIN ANALYZE SELECT nextval('s')",
                "EXPLAIN /* c */ ANALYZE SELECT nextval('s')",
                "EXPLAIN -- c\n ANALYZE SELECT nextval('s')",
                "EXPLAIN (ANALYZE) SELECT nextval('s')",
                "EXPLAIN (ANALYZE true) SELECT nextval('s')",
                "EXPLAIN (FORMAT JSON, ANALYZE) SELECT nextval('s')",
                "CREATE TABLE t AS SELECT 1; SELECT nextval('s')",
                "EXPLAIN SELECT * FROM range(nextval('s'))",
                "CREATE TABLE t AS SELECT * FROM range(nextval('s'))",
                "EXPLAIN SELECT * FROM query('SELECT * FROM range(nextval(''s''))')",
            )) {
                assertEquals(sql, DuckdbEngineValidator.Verdict.Unavailable,
                    DuckdbEngineValidator.verdict(sql, connection))
            }
            for (sql in listOf("SELECT 1; SELECT nextval('s')", "SELECT * FROM range(nextval('s'))",
                "SELECT * FROM query('SELECT * FROM range(nextval(''s''))')")) {
                assertEquals(sql, DuckdbEngineValidator.Verdict.Clean, DuckdbEngineValidator.verdict(sql, connection))
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT nextval('s')").use { result ->
                    assertTrue(result.next())
                    assertEquals(1L, result.getLong(1))
                }
            }
        }
    }

    @Test
    fun `file reading select is parsed without requiring a readable file`() {
        val file = Files.createTempFile("duckdb-validation", ".csv")
        Files.delete(file)
        val sql = "SELECT * FROM read_csv('${file.toString().replace("'", "''")}')"
        assertEquals(listOf(DuckdbEngineValidator.Verdict.Clean), DuckdbEngineValidator.verdictsWithDriver(listOf(sql), DuckDBDriver()))
        assertTrue(Files.notExists(file))
    }

    @Test
    fun `url reading select sends no request`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/data.csv") { exchange ->
            requests.incrementAndGet()
            val bytes = "x\n1\n".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val sql = "SELECT * FROM read_csv('http://127.0.0.1:${server.address.port}/data.csv')"
            assertEquals(listOf(DuckdbEngineValidator.Verdict.Clean), DuckdbEngineValidator.verdictsWithDriver(listOf(sql), DuckDBDriver()))
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `unsupported serialization is unavailable and never creates a table`() {
        DuckdbEngineValidator.connect(DuckDBDriver())!!.use { connection ->
            assertEquals(DuckdbEngineValidator.Verdict.Unavailable,
                DuckdbEngineValidator.verdict("CREATE TABLE t AS SELECT * FROM read_csv('/not-readable.csv')", connection))
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_name = 't'").use {
                    assertTrue(!it.next())
                }
            }
        }
    }

    @Test
    fun `ordinary syntax errors reported and missing engine unavailable`() {
        assertTrue(DuckdbEngineValidator.verdictsWithDriver(listOf("SELECT FROM WHERE"), DuckDBDriver()).single()
            is DuckdbEngineValidator.Verdict.Flagged)
        assertEquals(listOf(DuckdbEngineValidator.Verdict.Unavailable),
            DuckdbEngineValidator.verdictsWithDriver(listOf("SELECT 1"), null))
    }
}
