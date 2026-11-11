package dev.sort.duckdb.probe

import com.gizmodata.quack.jdbc.sql.QuackConnection
import com.gizmodata.quack.jdbc.message.QuackMessage
import com.gizmodata.quack.jdbc.transport.QuackTransport
import com.gizmodata.quack.jdbc.transport.QuackTransportFactory
import com.gizmodata.quack.jdbc.transport.QuackUri
import com.intellij.database.remote.jdbc.helpers.GenericJdbcHelper
import com.intellij.database.remote.jdbc.impl.RemoteConnectionImpl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.http.HttpTimeoutException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Real 0.14.0 JDBC-over-v3 tests, never a user's server or a native Quack ATTACH client. */
class QuackV3CancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val heavy = "WITH started AS MATERIALIZED (SELECT nextval('cancel_started') AS n) " +
        "SELECT sum((a.range + b.range + s.n) % 7) FROM range(400000) a, range(400000) b, started s"

    @Test(timeout = 60_000)
    fun `standard JDBC cancel interrupts a v3 query and preserves its connection`() = cancellation(false)

    @Test(timeout = 60_000)
    fun `GenericJdbcHelper remote statement cancel reaches the v3 driver`() = cancellation(true)

    @Test(timeout = 60_000)
    fun `JDBC preserves count-shaped rows and separates DML counts from end of results`() {
        Server().use { server ->
            server.connect().use { jdbc ->
                assertEquals("0.14.0", jdbc.metaData.driverVersion)
                jdbc.createStatement().use { statement ->
                    statement.execute("CREATE TABLE result_kind (id BIGINT)")
                    fun rows(sql: String, expected: List<Long>) {
                        assertTrue("Must remain a result set: $sql", statement.execute(sql))
                        assertEquals(-1, statement.updateCount)
                        val values = mutableListOf<Long>()
                        statement.resultSet.use { while (it.next()) values += it.getLong(1) }
                        assertEquals(sql, expected, values)
                        assertFalse(statement.moreResults)
                        assertEquals("Draining must terminate", -1, statement.updateCount)
                    }
                    fun count(sql: String, expected: Int) {
                        assertFalse("Must be an affected-row count: $sql", statement.execute(sql))
                        assertNull(statement.resultSet)
                        assertEquals(sql, expected, statement.updateCount)
                        assertFalse(statement.moreResults)
                        assertEquals("Draining must terminate", -1, statement.updateCount)
                    }
                    for (label in listOf("Count", "rows_affected", "count_star()")) {
                        rows("SELECT 7::BIGINT AS \"$label\"", listOf(7L))
                    }
                    rows("SELECT count(*) FROM range(3)", listOf(3L))
                    rows("SELECT -1::BIGINT AS rows_affected", listOf(-1L))
                    rows("SELECT 1::BIGINT AS Count WHERE false", emptyList())
                    rows("SELECT i::BIGINT AS Count FROM range(3) t(i) ORDER BY i", listOf(0L, 1L, 2L))
                    count("INSERT INTO result_kind VALUES (1), (2)", 2)
                    count("UPDATE result_kind SET id = id + 10", 2)
                    count("DELETE FROM result_kind WHERE false", 0)
                    count("WITH vals AS (SELECT 3 AS id) INSERT INTO result_kind SELECT id FROM vals", 1)
                    rows("INSERT INTO result_kind VALUES (4) RETURNING id AS Count", listOf(4L))
                    rows("UPDATE result_kind SET id = 5 WHERE id = 4 RETURNING id AS rows_affected", listOf(5L))
                    println("Quack 0.14.0 JDBC SELECT/RETURNING rows, DML counts and bounded result draining passed")
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun `IDE remote wrapper preserves result sets and terminates DML draining`() {
        Server().use { server ->
            server.connect().use { jdbc ->
                val remote = RemoteConnectionImpl(jdbc, GenericJdbcHelper())
                val statement = remote.createStatement()
                try {
                    statement.execute("CREATE TABLE remote_result_kind (id BIGINT)")
                    assertFalse(statement.execute("INSERT INTO remote_result_kind VALUES (1), (2)"))
                    assertEquals(2, statement.updateCount)
                    assertFalse(statement.moreResults)
                    assertEquals(-1, statement.updateCount)
                    for (sql in listOf("SELECT 7::BIGINT AS Count", "SELECT count(*) FROM remote_result_kind",
                        "INSERT INTO remote_result_kind VALUES (3) RETURNING id AS rows_affected")) {
                        assertTrue("IDE must receive rows for $sql", statement.execute(sql))
                        assertEquals(-1, statement.updateCount)
                        assertNotNull(statement.resultSet)
                        statement.resultSet.close()
                        assertFalse(statement.moreResults)
                        assertEquals(-1, statement.updateCount)
                    }
                    println("Quack 0.14.0 GenericJdbcHelper result-kind and end-of-results checks passed")
                } finally {
                    statement.close()
                    remote.close()
                }
            }
        }
    }

    private fun cancellation(remotePath: Boolean) {
        Server().use { server ->
            server.connect().use { jdbc ->
                assertEquals(3L, (jdbc as QuackConnection).session().protocolVersion())
                assertEquals("0.14.0", jdbc.metaData.driverVersion)
                val sessionId = jdbc.session().connectionId()
                server.connect().use { control ->
                    control.createStatement().use { it.execute("CREATE SEQUENCE cancel_started") }
                    val remote = if (remotePath) RemoteConnectionImpl(jdbc, GenericJdbcHelper()) else null
                    val native = if (remotePath) null else jdbc.createStatement()
                    val statement = remote?.createStatement()
                    val pool = Executors.newSingleThreadExecutor { task -> Thread(task, "quack-cancel-query").apply { isDaemon = true } }
                    try {
                        // Published driver contract: JDBC queryTimeout is not a query deadline.
                        if (statement != null) {
                            statement.queryTimeout = 1
                            assertEquals(0, statement.queryTimeout)
                        } else {
                            native!!.queryTimeout = 1
                            assertEquals(0, native.queryTimeout)
                        }
                        val query = pool.submit<Throwable?> {
                            try {
                                if (statement != null) statement.executeQuery(heavy)?.close()
                                else native!!.executeQuery(heavy).use { it.next() }
                                null
                            } catch (failure: Throwable) { failure }
                        }
                        // Observe a materialized CTE side effect through a separate connection.
                        // This proves PREPARE installed the query UUID before another thread cancels.
                        awaitStarted(control) { query.isDone }
                        assertFalse("The expensive query must still be running", query.isDone)
                        val begin = System.nanoTime()
                        if (statement != null) statement.cancel() else native!!.cancel()
                        val failure = query.get(6, TimeUnit.SECONDS)
                        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
                        assertTrue("Cancellation was not prompt: ${elapsedMs}ms", elapsedMs < 6_000)
                        assertNotNull("The query must be interrupted, not finish normally", failure)
                        assertTrue("Expected SQLSTATE 57014, got $failure", causes(failure).any { it is SQLException && it.sqlState == "57014" })
                        assertFalse("A confirmed cancel must preserve the connection", jdbc.isClosed)
                        assertEquals(sessionId, jdbc.session().connectionId())
                        jdbc.createStatement().use { s ->
                            s.executeQuery("SELECT 42").use { assertTrue(it.next()); assertEquals(42, it.getInt(1)) }
                        }
                        println("Quack 0.14.0 v3 ${if (remotePath) "RemoteStatement/GenericJdbcHelper" else "Statement"}.cancel: ${elapsedMs}ms; same-session SELECT 42 passed")
                    } finally {
                        pool.shutdownNow()
                        // Kill our server first even if cancel regresses, so close cannot strand a query.
                        server.close()
                        statement?.close()
                        native?.close()
                        remote?.close()
                        assertTrue("Query worker must terminate", pool.awaitTermination(5, TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun `requestTimeout bounds a real v3 HTTP query but is not JDBC queryTimeout`() {
        Server().use { server ->
            server.connect("PT1S").use { jdbc ->
                jdbc.createStatement().use { s ->
                    s.execute("CREATE SEQUENCE cancel_started")
                    s.queryTimeout = 1
                    assertEquals("Do not promise unsupported JDBC query deadlines", 0, s.queryTimeout)
                    val begin = System.nanoTime()
                    val failure = assertThrows(SQLException::class.java) { s.executeQuery(heavy).close() }
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
                    assertTrue("Expected an HTTP request timeout, got $failure", causes(failure).any { it is HttpTimeoutException })
                    assertTrue("requestTimeout must bound the request: ${elapsedMs}ms", elapsedMs in 500..8_000)
                    println("Quack 0.14.0 requestTimeout=PT1S: ${elapsedMs}ms; JDBC queryTimeout remains unsupported")
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun `stalled CANCEL is bounded and an unconfirmed session is closed`() {
        Server().use { server ->
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val factory = QuackTransportFactory { uri ->
                val http = QuackTransportFactory.http().create(uri)
                object : QuackTransport {
                    override fun setProtocolVersion(version: Long) = http.setProtocolVersion(version)
                    override fun send(request: QuackMessage): QuackMessage {
                        if (request !is QuackMessage.CancelRequest) return http.send(request)
                        entered.countDown()
                        try {
                            // Test an I/O implementation that ignores interruption. Teardown releases it.
                            while (release.count > 0) {
                                try { release.await() } catch (_: InterruptedException) { }
                            }
                            error("Stalled CANCEL fixture released")
                        } finally { exited.countDown() }
                    }
                }
            }
            QuackConnection(QuackUri.parse(server.url()), factory).use { jdbc ->
                server.connect().use { control ->
                    control.createStatement().use { it.execute("CREATE SEQUENCE cancel_started") }
                    jdbc.createStatement().use { statement ->
                        val pool = Executors.newFixedThreadPool(2) { task -> Thread(task, "quack-stalled-control").apply { isDaemon = true } }
                        try {
                            val query = pool.submit<Throwable?> {
                                try { statement.executeQuery(heavy).use { it.next() }; null }
                                catch (failure: Throwable) { failure }
                            }
                            awaitStarted(control) { query.isDone }
                            val begin = System.nanoTime()
                            val cancelled = pool.submit<Throwable?> {
                                try { statement.cancel(); null } catch (failure: Throwable) { failure }
                            }
                            assertTrue("The test must stall an actual CANCEL request", entered.await(2, TimeUnit.SECONDS))
                            val failure = cancelled.get(6, TimeUnit.SECONDS)
                            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
                            assertTrue("Unconfirmed CANCEL must report a JDBC error: $failure", failure is SQLException)
                            assertTrue("Expected the control deadline: $failure", causes(failure).any { it is TimeoutException })
                            assertTrue("Control deadline must bound cancellation: ${elapsedMs}ms", elapsedMs in 1_500..5_500)
                            assertTrue("Unconfirmed cancellation must close the session", jdbc.isClosed)
                            assertThrows(SQLException::class.java) { jdbc.createStatement() }
                            assertNotNull("The running query must not finish successfully", query.get(6, TimeUnit.SECONDS))
                            println("Quack 0.14.0 stalled CANCEL: ${elapsedMs}ms; unconfirmed session closed")
                        } finally {
                            release.countDown()
                            pool.shutdownNow()
                            server.close()
                            assertTrue("Injected CANCEL worker must exit", exited.await(5, TimeUnit.SECONDS))
                            assertTrue("Query/control workers must exit", pool.awaitTermination(5, TimeUnit.SECONDS))
                        }
                    }
                }
            }
        }
    }

    private fun awaitStarted(control: Connection, finished: () -> Boolean) {
        val readyBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        var started = false
        while (!started && !finished() && System.nanoTime() < readyBy) {
            control.createStatement().use { s ->
                s.executeQuery("SELECT last_value FROM duckdb_sequences() WHERE sequence_name='cancel_started'").use {
                    started = it.next() && it.getLong(1) == 1L && !it.wasNull()
                }
            }
            if (!started) Thread.sleep(20)
        }
        assertTrue("The server must start the expensive query before cancellation", started)
    }

    private fun causes(failure: Throwable?) = generateSequence(failure) { it.cause }.toList()

    private fun classpathEntry(type: Class<*>): File {
        // IntelliJ's test classloader can have a null CodeSource location. Resolve the
        // actual class resource instead, never a guessed checkout/build directory.
        val name = type.name.replace('.', '/') + ".class"
        val resource = requireNotNull(type.getResource("/$name"))
        return when (resource.protocol) {
            "file" -> name.split('/').fold(File(resource.toURI())) { file, _ -> file.parentFile }
            "jar" -> File(URI(resource.toExternalForm().removePrefix("jar:").substringBefore("!/")))
            else -> error("Unsupported test class resource: $resource")
        }
    }

    private inner class Server : AutoCloseable {
        private val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        private val token = UUID.randomUUID().toString()
        private val directory = temporary.newFolder()
        private val errors = File(directory, "server-stderr.log")
        private val process: Process
        private var stopped = false

        init {
            // A minimal child classpath: our Java fixture and the real native JDBC driver only.
            val classpath = listOf(QuackV3ServerMain::class.java, org.duckdb.DuckDBDriver::class.java)
                .map { classpathEntry(it).absolutePath }.distinct().joinToString(File.pathSeparator)
            process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-cp", classpath, QuackV3ServerMain::class.java.name, port.toString(), token,
                requireNotNull(System.getProperty("test.httpfsExtension")),
                requireNotNull(System.getProperty("test.quackExtension")), directory.absolutePath)
                .redirectError(errors).start()
            try {
                CompletableFuture.supplyAsync {
                    process.inputStream.bufferedReader().lineSequence().first { it == "READY" }
                }.get(20, TimeUnit.SECONDS)
            } catch (failure: Throwable) {
                close()
                throw AssertionError("Isolated signed Quack server failed: ${errors.readText()}", failure)
            }
        }

        fun url(timeout: String = "PT8S") =
            "jdbc:quack://127.0.0.1:$port?token=$token&tls=false&connectTimeout=PT2S&requestTimeout=$timeout"

        fun connect(timeout: String = "PT8S"): Connection = DriverManager.getConnection(url(timeout))

        override fun close() {
            if (stopped) return
            stopped = true
            try {
                process.outputStream.close()
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "Test-owned Quack process did not terminate" }
                }
            } finally {
                // Also kill it if JUnit's test deadline interrupts cleanup.
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}
