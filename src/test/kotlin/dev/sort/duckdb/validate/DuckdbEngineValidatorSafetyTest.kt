package dev.sort.duckdb.validate

import com.sun.net.httpserver.HttpServer
import com.intellij.openapi.progress.ProcessCanceledException
import dev.brikk.house.sql.verify.ParseOnlyDuckdbVerifier
import dev.brikk.house.sql.verify.VerifyResult
import org.duckdb.DuckDBDriver
import org.junit.Assert.*
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
                assertTrue(DuckdbEngineValidator.verdictsWithDriver(listOf("SELECT 1"), driver).single() is VerifyResult.Unavailable)
            }
        }
    }

    @Test
    fun `cancellation during parser query propagates`() {
        val native = DuckDBDriver()
        native.connect("jdbc:duckdb:", Properties()).use { connection ->
            for (cancellation in listOf(ProcessCanceledException(), CancellationException())) {
                val driver = object : Driver by native {
                    override fun connect(url: String?, info: Properties?): Connection = object : Connection by connection {
                        override fun createStatement(): Statement = throw cancellation
                        override fun close() = Unit
                    }
                }
                assertSame(cancellation, assertThrows(cancellation.javaClass) {
                    DuckdbEngineValidator.verdictsWithDriver(listOf("SELECT 1"), driver)
                })
            }
        }
    }

    @Test
    fun `explain execution forms never advance a sequence`() {
        withVerifier { verifier, connection ->
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
                assertTrue("$sql must not be accepted", verifier.verify(sql) is VerifyResult.Unverified)
            }
            for (sql in listOf("SELECT 1; SELECT nextval('s')", "SELECT * FROM range(nextval('s'))",
                "SELECT * FROM query('SELECT * FROM range(nextval(''s''))')")) {
                assertEquals(sql, VerifyResult.Accepted, verifier.verify(sql))
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
            val sql = "SELECT * FROM read_csv('${fifo.toString().replace("'", "''")}')"
            ParseOnlyDuckdbVerifier(DuckDBDriver()).use { verifier ->
                assertEquals(VerifyResult.Accepted, verifier.verify(sql))
                assertFalse(accessed.get())
            }
            requireNotNull(DuckDBDriver().connect("jdbc:duckdb:", Properties())).use { connection ->
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
            assertEquals(listOf(VerifyResult.Accepted), DuckdbEngineValidator.verdictsWithDriver(listOf(sql), DuckDBDriver()))
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `unsupported serialization is unverified and never creates a table`() {
        withVerifier { verifier, connection ->
            assertTrue(verifier.verify("CREATE TABLE t AS SELECT * FROM read_csv('/not-readable.csv')") is VerifyResult.Unverified)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_name = 't'").use {
                    assertFalse(it.next())
                }
            }
        }
    }

    @Test
    fun `ordinary syntax errors reported and missing engine unavailable`() {
        assertTrue(DuckdbEngineValidator.available)
        assertTrue(DuckdbEngineValidator.verdicts(listOf("SELECT FROM WHERE")).single() is VerifyResult.Rejected)
        assertTrue(DuckdbEngineValidator.verdictsWithDriver(listOf("SELECT 1"), null).single() is VerifyResult.Unavailable)
        assertTrue(DuckdbEngineValidator.verdicts(listOf("SELECT 1"), "/missing/duckdb.jar").single() is VerifyResult.Unavailable)
    }

    private fun withVerifier(action: (ParseOnlyDuckdbVerifier, Connection) -> Unit) {
        val native = DuckDBDriver()
        var connection: Connection? = null
        val driver = object : Driver by native {
            override fun connect(url: String?, info: Properties?): Connection? = native.connect(url, info).also { connection = it }
        }
        ParseOnlyDuckdbVerifier(driver).use { verifier ->
            assertEquals(VerifyResult.Accepted, verifier.verify("SELECT 1"))
            action(verifier, requireNotNull(connection))
        }
    }
}
