package dev.sort.duckdb.grammar

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import dev.brikk.house.sql.intellij.catalog.executeStatement
import dev.brikk.house.sql.intellij.catalog.queryRows
import dev.brikk.house.sql.verify.DuckdbQueryConnection
import java.sql.SQLException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only an isolated helper, never a user console. Shared by parsing and native completion. */
internal class DuckdbGrammarHelper(
    private val connection: DatabaseConnectionCore,
    private val release: () -> Unit,
    private val expected: DuckdbGrammarProfile,
    private val current: () -> Boolean,
) : DuckdbQueryConnection, GrammarConnection {
    @Volatile private var running: RemoteStatement? = null
    private val cancelled = AtomicBoolean()
    private val closed = AtomicBoolean()

    fun checkCurrent() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("Native helper cancelled")
        if (!current()) throw SQLException("Grammar profile or runtime changed")
    }

    fun rows(sql: String, maxRows: Int, timeoutSeconds: Int): List<Map<String, Any?>> {
        checkCurrent()
        return connection.queryRows(sql, maxRows, timeoutSeconds, onStatement = { running = it }, isCancelled = cancelled::get)
    }
    override fun rows(sql: String) = rows(sql, 4096, 2)
    override fun execute(sql: String) {
        checkCurrent()
        connection.executeStatement(sql, 2, onStatement = { running = it }, isCancelled = cancelled::get)
    }
    fun checkActivation() {
        val after = DuckdbGrammarDiscovery.effective(rows(DuckdbGrammarDiscovery.EFFECTIVE, 1, 2).firstOrNull()?.values?.firstOrNull()?.toString())
        if (after != expected.enabled) throw SQLException("Grammar activation changed during native checking")
        checkCurrent()
    }
    override fun queryString(sql: String, timeoutSeconds: Int): String? {
        val document = rows(sql, 1, timeoutSeconds).firstOrNull()?.values?.firstOrNull()?.toString()
        checkActivation()
        return document
    }
    override fun cancel() {
        cancelled.set(true)
        running?.let { statement -> JdbcNativeUtil.performSafe { statement.cancel() } }
    }
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
