package dev.sort.duckdb.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deterministic debounce semantics via the injected scheduler seam — no clocks, no sleeps: the
 * fake captures scheduled tasks and the test fires them by hand.
 */
class DuckdbRefreshDebouncerTest {

    private class FakeScheduler {
        val scheduled = ArrayList<Pair<Long, () -> Unit>>()
        fun asSeam(): (Long, () -> Unit) -> Unit = { delay, task -> scheduled.add(delay to task) }
        fun fireAll() {
            // Drain a snapshot: a fired task may schedule anew (not in these tests, but safe).
            val snapshot = ArrayList(scheduled)
            scheduled.clear()
            snapshot.forEach { it.second() }
        }
    }

    @Test
    fun `burst of submits for one key coalesces into one scheduled run`() {
        val scheduler = FakeScheduler()
        val debouncer = DuckdbRefreshDebouncer(delayMillis = 2_000, schedule = scheduler.asSeam())
        var runs = 0
        repeat(5) { debouncer.submit("ds-1") { runs++ } } // INSTALL x; LOAD x; INSTALL y; ... burst
        assertEquals("one window -> one scheduled task", 1, scheduler.scheduled.size)
        assertEquals("the configured delay is what gets scheduled", 2_000L, scheduler.scheduled[0].first)
        assertEquals("nothing runs until the window fires", 0, runs)
        scheduler.fireAll()
        assertEquals("the burst coalesced into exactly one run", 1, runs)
    }

    @Test
    fun `after the window fires a new submit opens a new window`() {
        val scheduler = FakeScheduler()
        val debouncer = DuckdbRefreshDebouncer(delayMillis = 2_000, schedule = scheduler.asSeam())
        var runs = 0
        debouncer.submit("ds-1") { runs++ }
        scheduler.fireAll()
        assertEquals(1, runs)
        debouncer.submit("ds-1") { runs++ } // a later INSTALL must not be swallowed forever
        assertEquals(1, scheduler.scheduled.size)
        scheduler.fireAll()
        assertEquals(2, runs)
    }

    @Test
    fun `distinct keys debounce independently`() {
        val scheduler = FakeScheduler()
        val debouncer = DuckdbRefreshDebouncer(delayMillis = 2_000, schedule = scheduler.asSeam())
        val runsByKey = LinkedHashMap<String, Int>()
        fun bump(key: String): () -> Unit = { runsByKey.merge(key, 1, Int::plus) }
        debouncer.submit("ds-1", bump("ds-1"))
        debouncer.submit("ds-2", bump("ds-2"))
        debouncer.submit("ds-1", bump("ds-1")) // coalesces into ds-1's open window
        assertEquals("one task per key", 2, scheduler.scheduled.size)
        scheduler.fireAll()
        assertEquals(mapOf("ds-1" to 1, "ds-2" to 1), runsByKey)
    }

    @Test
    fun `only the window-opening action runs - coalesced submits are dropped`() {
        val scheduler = FakeScheduler()
        val debouncer = DuckdbRefreshDebouncer(delayMillis = 2_000, schedule = scheduler.asSeam())
        val ran = ArrayList<String>()
        debouncer.submit("ds-1") { ran.add("first") }
        debouncer.submit("ds-1") { ran.add("second") }
        scheduler.fireAll()
        assertEquals(listOf("first"), ran)
    }
}
