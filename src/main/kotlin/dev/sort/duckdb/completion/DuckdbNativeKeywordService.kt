package dev.sort.duckdb.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.notification.NotificationType
import dev.brikk.house.sql.intellij.notifyEscaped
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.sort.duckdb.grammar.DuckdbGrammarHelper
import dev.sort.duckdb.grammar.DuckdbGrammarVerification
import dev.sort.duckdb.grammar.GrammarIdentity
import dev.sort.duckdb.grammar.GrammarTarget
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The worker receives copied SQL and connection identity, never PSI or an editor. */
@Service(Service.Level.PROJECT)
internal class DuckdbNativeKeywordService(private val project: Project) : Disposable {
    private data class Key(val identity: GrammarIdentity, val prefix: String)
    private val cache = object : LinkedHashMap<Key, NativeKeywords>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, NativeKeywords>?) = size > 32
    }
    private val statuses = mutableMapOf<String, Pair<GrammarTarget, String>>()
    private var lastNotice: Pair<String, Long>? = null
    private val workers = ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8), { task ->
        Thread(task, "duckdb-native-keywords").apply { isDaemon = true }
    })
    fun status(sourceId: String): String? = synchronized(statuses) { statuses[sourceId] }?.let {
        if (project.service<DuckdbGrammarVerification>().isCurrent(it.first)) it.second else null
    }

    fun describe(source: LocalDataSource): String {
        val location = source.url?.removePrefix("jdbc:duckdb:")?.substringBefore(';')?.substringBefore('?')?.trim()
        if (source.url?.startsWith("jdbc:duckdb:") == true && (location.isNullOrEmpty() || location.startsWith(":memory:"))) {
            return "Native keyword completion is unavailable for this unnamed in-memory source: no isolated helper can share its instance. Loading autocomplete does not enable this mode. No autocomplete query runs on your live console."
        }
        if (!DuckdbGrammarVerification.isFileBacked(source.url)) return "Native keyword completion is unavailable without a matching isolated helper for this source."
        return status(source.uniqueId) ?: "Native keyword completion has not been checked. It requires separately loaded autocomplete. Console menu: Check native keyword completion."
    }

    fun announceUnavailable(target: GrammarTarget, message: String) {
        val key = target.identity.sourceId + message
        val now = System.nanoTime()
        synchronized(statuses) {
            if (lastNotice?.let { it.first == key && now - it.second < TimeUnit.SECONDS.toNanos(10) } == true) return
            lastNotice = key to now
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) notifyEscaped(project, "DuckDB (Brikk)", "Native keyword completion", message, NotificationType.INFORMATION)
        }
    }

    fun complete(target: GrammarTarget, prefix: NativeKeywordPrefix, checkCanceled: () -> Unit = ProgressManager::checkCanceled): NativeKeywords {
        val result = compute(target, prefix, checkCanceled)
        val source = target.source.get()
        val message = if (target.identity.unavailable != null && source != null && !DuckdbGrammarVerification.isFileBacked(source.url)) describe(source) else result.status
        val described = result.copy(status = message)
        synchronized(statuses) { statuses[target.identity.sourceId] = target to (message ?: "Native keyword completion is available.") }
        return described
    }

    private fun compute(target: GrammarTarget, prefix: NativeKeywordPrefix, checkCanceled: () -> Unit): NativeKeywords {
        checkCanceled()
        val factory = project.service<DuckdbGrammarVerification>()
        target.identity.unavailable?.let { return NativeKeywords(status = it) }
        if (!factory.isCurrent(target)) return NativeKeywords(status = "Grammar state changed before completion.")
        val key = Key(target.identity, prefix.sql)
        synchronized(cache) { cache[key] }?.let { return it }
        val cancelled = AtomicBoolean()
        val opened = AtomicReference<DuckdbGrammarHelper?>()
        val future = try {
            workers.submit<NativeKeywords> {
                recoverEditorOperation {
                    val helper = factory.openHelper(target) ?: return@recoverEditorOperation NativeKeywords(status = "No matching grammar helper is available.")
                    helper.use {
                        opened.set(helper)
                        if (cancelled.get()) return@use NativeKeywords()
                        val result = DuckdbNativeKeywords.query(prefix) { sql, count ->
                            if (cancelled.get()) throw java.util.concurrent.CancellationException()
                            helper.rows(sql, count, 1)
                        }
                        helper.checkActivation()
                        result
                    }
                }.getOrElse { NativeKeywords(status = "Native keywords unavailable: ${it.message?.lineSequence()?.firstOrNull()?.take(180) ?: "helper query failed"}") }
            }
        } catch (_: RejectedExecutionException) { return NativeKeywords(status = "Native keyword completion is busy.") }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
        try {
            while (true) {
                checkCanceled()
                if (System.nanoTime() >= deadline) return NativeKeywords(status = "Native keyword completion timed out; existing completion remains available.")
                try {
                    val result = future.get(20, TimeUnit.MILLISECONDS)
                    if (!factory.isCurrent(target)) return NativeKeywords(status = "Grammar state changed during completion.")
                    synchronized(cache) { cache[key] = result }
                    return result
                } catch (_: TimeoutException) { }
            }
        } catch (e: ExecutionException) { throw e.cause ?: e }
        finally {
            if (!future.isDone) {
                cancelled.set(true)
                CompletableFuture.runAsync { opened.get()?.cancel() }
                future.cancel(true)
            }
        }
    }
    override fun dispose() { workers.shutdownNow(); synchronized(cache) { cache.clear() } }
}
