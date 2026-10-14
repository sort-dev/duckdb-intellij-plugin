package dev.sort.duckdb.catalog

import dev.brikk.house.sql.intellij.catalog.LiveCatalogProfile
import dev.brikk.house.sql.intellij.catalog.RefreshReason
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/** Deterministic scheduler drives the real shared service's debounce path; no production replica. */
@OptIn(InternalCoroutinesApi::class)
class DuckdbRefreshDebouncerTest {
    private class Scheduler : CoroutineDispatcher(), Delay {
        val queue = ArrayDeque<Runnable>()
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue += block }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            scheduled += timeMillis to Runnable { if (continuation.isActive) continuation.resume(Unit) }
        }
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            val entry = timeMillis to block
            scheduled += entry
            return DisposableHandle { scheduled.remove(entry) }
        }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        fun fireAll() { val pending = scheduled.toList(); scheduled.clear(); pending.forEach { it.second.run() }; drain() }
    }
    private fun run(action: (LegacyCatalogFixture, Scheduler, ParityCatalogService, LiveCatalogProfile, (Boolean) -> Unit) -> Unit) = legacyCatalog { fixture ->
        var allowRefresh = true
        val profile = object : LiveCatalogProfile by DuckdbLiveCatalogProfile {
            override val enabled get() = allowRefresh
            override val refreshDelay = 2.seconds
        }
        val scheduler = Scheduler()
        val job = SupervisorJob()
        val service = ParityCatalogService(CoroutineScope(job + scheduler), profile, fixture.scratch)
        try { action(fixture, scheduler, service, profile) { allowRefresh = it } } finally { job.cancel(); scheduler.drain() }
    }
    @Test fun `burst of submits for one key coalesces into one scheduled run`() = run { fixture, timer, service, _, enable ->
        val source = fixture.dataSource()
        RecordingConsole(fixture.testProject, fixture.sqlFile().virtualFile, source).use { console ->
            repeat(5) { service.inventoryChanged(fixture.testProject, console.console.session, null, sessionFacts = false) }
            timer.drain()
            assertEquals(1, timer.scheduled.size); assertEquals(2000L, timer.scheduled.single().first); assertTrue(service.events.isEmpty())
            enable(false); timer.fireAll(); assertEquals(1, service.events.size)
        }
    }
    @Test fun `after the window fires a new submit opens a new window`() = run { fixture, timer, service, _, enable ->
        RecordingConsole(fixture.testProject, fixture.sqlFile().virtualFile, fixture.dataSource()).use { console ->
            repeat(2) {
                enable(true); service.inventoryChanged(fixture.testProject, console.console.session, null, false); timer.drain()
                assertEquals(1, timer.scheduled.size); enable(false); timer.fireAll()
            }
            assertEquals(2, service.events.size)
        }
    }
    @Test fun `distinct keys debounce independently`() = run { fixture, timer, service, _, enable ->
        val one = fixture.dataSource(); val two = fixture.dataSource()
        RecordingConsole(fixture.testProject, fixture.sqlFile().virtualFile, one).use { a ->
            RecordingConsole(fixture.testProject, fixture.sqlFile().virtualFile, two).use { b ->
                for (console in listOf(a, b, a)) service.inventoryChanged(fixture.testProject, console.console.session, null, false)
                timer.drain(); assertEquals(2, timer.scheduled.size); enable(false); timer.fireAll()
                assertEquals(setOf(one.uniqueId, two.uniqueId), service.events.map { it.first }.toSet())
            }
        }
    }
    @Test fun `only the window-opening action runs - coalesced submits are dropped`() = run { fixture, timer, service, _, enable ->
        RecordingConsole(fixture.testProject, fixture.sqlFile().virtualFile, fixture.dataSource()).use { console ->
            service.inventoryChanged(fixture.testProject, console.console.session, null, false, notify = false)
            service.inventoryChanged(fixture.testProject, console.console.session, null, false, notify = true)
            timer.drain(); enable(false); timer.fireAll()
            assertEquals(RefreshReason.Executed(false), service.events.single().second)
        }
    }
}
