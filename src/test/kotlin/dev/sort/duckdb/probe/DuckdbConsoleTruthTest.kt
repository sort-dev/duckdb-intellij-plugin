package dev.sort.duckdb.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * STAGE-6 TRUTH: the console behaviors a DuckDB (Brikk) query console leans on, measured against the
 * NATIVE driver in-process (:memory:). Cancel, grid-shaped statements, and transaction semantics.
 *
 * The quack driver's own cancel() is a known no-op (bytecode: literal `return`, overriding the
 * skeletal `throw notSupported`) — that is a driver-team item restated in REPORT-stage6-console.md,
 * not something this offline test can exercise (no local GizmoSQL server).
 */
class DuckdbConsoleTruthTest {

    private fun colCount(rs: ResultSet) = rs.metaData.columnCount
    private fun rowCount(rs: ResultSet): Int { var n = 0; while (rs.next()) n++; return n }

    @Test
    fun `Statement cancel aborts a long query and the connection stays usable`() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { stmt ->
                // ~1.6e11-row cross join with a modulo filter: minutes of compute, so it cannot
                // finish before we cancel; count(*) keeps memory flat.
                val heavy = "SELECT count(*) FROM range(400000) AS t1, range(400000) AS t2 " +
                    "WHERE (t1.range + t2.range) % 7 = 0"
                val error = arrayOfNulls<Throwable>(1)
                val finishedNormally = booleanArrayOf(false)
                val start = System.currentTimeMillis()
                val worker = Thread {
                    try {
                        stmt.executeQuery(heavy).use { rs -> rs.next(); finishedNormally[0] = true }
                    } catch (t: Throwable) {
                        error[0] = t
                    }
                }
                worker.isDaemon = true
                worker.start()
                Thread.sleep(400)
                stmt.cancel()
                worker.join(30_000)
                val elapsed = System.currentTimeMillis() - start
                println("=== cancel === finishedNormally=${finishedNormally[0]} elapsedMs=$elapsed thrown=${error[0]}")

                assertFalse("worker thread still running 30s after cancel", worker.isAlive)
                assertFalse("long query completed instead of being cancelled", finishedNormally[0])
                val thrown = error[0]
                assertTrue("cancel did not surface any error; got none", thrown != null)
                // SQLTimeoutException("INTERRUPT Error: Interrupted!") in practice — a SQLException.
                assertTrue("cancel error was not a SQLException: ${thrown!!.javaClass.name}", thrown is SQLException)
                assertTrue("cancel took implausibly long (${elapsed}ms) — not a prompt abort", elapsed < 25_000)
            }
            // The connection is not poisoned by the cancel: a fresh statement runs normally.
            c.createStatement().use { s ->
                s.executeQuery("SELECT 42").use { rs ->
                    rs.next()
                    assertEquals("connection unusable after cancel", 42, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `SUMMARIZE DESCRIBE PIVOT EXPLAIN each return a renderable result set`() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE sales (region VARCHAR, product VARCHAR, amt INTEGER)")
                s.execute("INSERT INTO sales VALUES ('E','a',10),('E','b',20),('W','a',5),('W','b',7)")
            }
            val cases = linkedMapOf(
                "SUMMARIZE" to "SUMMARIZE sales",
                "DESCRIBE" to "DESCRIBE sales",
                "PIVOT" to "PIVOT sales ON product USING sum(amt) GROUP BY region",
                "EXPLAIN" to "EXPLAIN SELECT * FROM sales",
            )
            for ((label, sql) in cases) {
                c.createStatement().use { s ->
                    s.executeQuery(sql).use { rs ->
                        val cols = colCount(rs)
                        val rows = rowCount(rs)
                        println("=== $label === cols=$cols rows=$rows")
                        assertTrue("$label produced no columns — grid would be empty", cols >= 1)
                        assertTrue("$label produced no rows — grid would be empty", rows >= 1)
                    }
                }
            }
        }
    }

    @Test
    fun `autocommit default is true and BEGIN ROLLBACK round-trips through plain execute`() {
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            assertTrue("expected autoCommit=true by default", c.autoCommit)
            c.createStatement().use { s ->
                s.execute("CREATE TABLE t (x INTEGER)")
                s.execute("INSERT INTO t VALUES (1),(2),(3)")
                assertEquals(3, scalar(s, "SELECT count(*) FROM t"))
                // Transaction control issued as plain statements (how a console submits SQL text).
                s.execute("BEGIN TRANSACTION")
                s.execute("INSERT INTO t VALUES (99)")
                assertEquals("insert not visible inside the transaction", 4, scalar(s, "SELECT count(*) FROM t"))
                s.execute("ROLLBACK")
                assertEquals("ROLLBACK via execute() did not undo the insert", 3, scalar(s, "SELECT count(*) FROM t"))
            }
        }
    }

    private fun scalar(s: Statement, sql: String): Int =
        s.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) }
}
