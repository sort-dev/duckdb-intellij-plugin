package dev.sort.duckdb.validate

import com.sun.net.httpserver.HttpServer
import com.intellij.openapi.progress.ProcessCanceledException
import org.duckdb.DuckDBDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Assume.assumeFalse
import java.net.InetSocketAddress
import java.nio.file.Files
import java.sql.Connection
import java.sql.Driver
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DuckdbEngineValidatorSafetyTest {
    @Test
    fun `native linkage failures make the engine unavailable on every pass`() {
        for (failure in listOf(ExceptionInInitializerError(UnsatisfiedLinkError("unsupported platform")),
            NoClassDefFoundError("org.duckdb.DuckDBNative"))) {
            val driver = object : Driver by DuckDBDriver() {
                override fun connect(url: String?, info: Properties?): Connection? = throw failure
            }
            repeat(2) {
                assertEquals(listOf(DuckdbEngineValidator.Verdict.Unavailable),
                    DuckdbEngineValidator.verdictsWithDriver(listOf("SELECT 1"), driver))
            }
        }
    }

    @Test
    fun `cancellation during parser query propagates`() {
        requireNotNull(DuckdbEngineValidator.connect(DuckDBDriver())).use { connection ->
            for (cancellation in listOf(ProcessCanceledException(), CancellationException())) {
                val interrupted = object : Connection by connection {
                    override fun createStatement(): Statement = throw cancellation
                }
                assertSame(cancellation, assertThrows(cancellation.javaClass) {
                    DuckdbEngineValidator.verdict("SELECT 1", interrupted)
                })
            }
        }
    }

    @Test
    fun `explain execution forms never advance a sequence`() {
        requireNotNull(DuckdbEngineValidator.connect(DuckDBDriver())).use { connection ->
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
    fun `file reading select is parsed without accessing a FIFO`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val directory = Files.createTempDirectory("duckdb-validation")
        val fifo = directory.resolve("data.csv")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.toString()).start().waitFor())
        val accessed = AtomicBoolean()
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val writer = executor.submit {
            started.countDown()
            Files.newOutputStream(fifo).use { stream ->
                accessed.set(true)
                stream.write("x\n1\n".toByteArray())
            }
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            requireNotNull(DuckDBDriver().connect("jdbc:duckdb:", Properties())).use { connection ->
                val sql = "SELECT * FROM read_csv('${fifo.toString().replace("'", "''")}')"
                assertEquals(DuckdbEngineValidator.Verdict.Clean, DuckdbEngineValidator.verdict(sql, connection))
                assertFalse(accessed.get())
                connection.createStatement().use { it.execute("EXPLAIN $sql") }
                writer.get(10, TimeUnit.SECONDS)
                assertTrue("the binding control must open the FIFO", accessed.get())
            }
        } finally {
            if (!accessed.get()) Files.newInputStream(fifo).use { it.readBytes() }
            writer.get(10, TimeUnit.SECONDS)
            executor.shutdown()
            directory.toFile().deleteRecursively()
        }
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
        requireNotNull(DuckdbEngineValidator.connect(DuckDBDriver())).use { connection ->
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
        assertTrue(DuckdbEngineValidator.available)
        assertTrue(DuckdbEngineValidator.verdicts(listOf("SELECT FROM WHERE")).single()
            is DuckdbEngineValidator.Verdict.Flagged)
        assertEquals(listOf(DuckdbEngineValidator.Verdict.Unavailable),
            DuckdbEngineValidator.verdictsWithDriver(listOf("SELECT 1"), null))
        assertEquals(listOf(DuckdbEngineValidator.Verdict.Unavailable),
            DuckdbEngineValidator.verdicts(listOf("SELECT 1"), "/missing/duckdb.jar"))
    }
}
