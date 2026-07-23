package dev.sort.duckdb.catalog

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Per-key trailing debounce for observer-triggered refreshes: the FIRST submit for a key opens a
 * window and schedules one fire after [delayMillis]; further submits for the same key inside the
 * window coalesce into that fire (an `INSTALL x; LOAD x; INSTALL y; LOAD y` script re-harvests
 * ONCE). The key clears just before the action runs, so an INSTALL arriving during the harvest
 * itself opens a fresh window — a change can be observed late but never lost.
 *
 * [schedule] is the seam that makes the logic deterministic under test (a fake captures the
 * delay+task instead of sleeping); production uses the app scheduled executor, and the action runs
 * on that pooled thread — callers must not assume the EDT.
 */
// API status: AppExecutorUtil.getAppScheduledExecutorService() is javap-verified free of ApiStatus
// flags on DataGrip 2026.1.3 (the class carries flagged members elsewhere; this one is clean).
class DuckdbRefreshDebouncer(
    private val delayMillis: Long = DEFAULT_DELAY_MILLIS,
    private val schedule: (delayMillis: Long, task: () -> Unit) -> Unit = { delay, task ->
        AppExecutorUtil.getAppScheduledExecutorService().schedule(Runnable { task() }, delay, TimeUnit.MILLISECONDS)
    },
) {
    companion object {
        /** ~2s: long enough to swallow a pasted INSTALL+LOAD run, short enough to feel live. */
        const val DEFAULT_DELAY_MILLIS = 2_000L
    }

    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Coalesced [action] for [key]; only the window-opening submit's action instance runs. */
    fun submit(key: String, action: () -> Unit) {
        if (!pending.add(key)) return // window already open for this key -> coalesce
        schedule(delayMillis) {
            pending.remove(key)
            action()
        }
    }
}
